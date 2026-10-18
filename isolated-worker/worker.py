"""Authenticated private-pipe supervisor. No listener, guest secrets, host mounts or source logs."""
import argparse
import base64
import concurrent.futures
import hashlib
import hmac
import json
import os
import queue
import re
import struct
import subprocess
import sys
import tempfile
import threading
import time
import uuid

FRAME_CAP = 131072
PROFILE = dict(wallTimeMs=1000, cpuTimeMs=250, processMemoryBytes=134217728,
               heapBytes=33554432, stackBytes=1048576, outputBytes=1024)
MANDATORY = ("filesystem", "network", "subprocess", "nonroot", "readonlyRoot",
             "pidNamespace", "processMemory", "cpuTime", "wallTime", "heap",
             "stack", "output", "fileDescriptors", "processCount", "freshContext",
             "moduleDenial", "cleanup", "imageIdentity", "cleanEnvironment")

def strict_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate field")
        result[key] = value
    return result

def require(condition):
    if not condition:
        raise ValueError("Integrity or capability verification failed")

def decode(raw):
    return json.loads(raw.decode("utf-8", "strict"), object_pairs_hook=strict_object,
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError("Constant")))

def read_frame(stream):
    header = stream.read(4)
    if not header:
        return None
    if len(header) != 4:
        raise ValueError("Truncated header")
    size = struct.unpack(">I", header)[0]
    if not 0 < size <= FRAME_CAP:
        raise ValueError("Frame cap")
    parts = bytearray()
    while len(parts) < size:
        chunk = stream.read(size - len(parts))
        if not chunk:
            raise ValueError("Truncated payload")
        parts.extend(chunk)
    return decode(parts)

class Supervisor:
    def __init__(self, docker, image, owner, config):
        if not re.fullmatch(r"sha256:[a-f0-9]{64}", image):
            raise ValueError("Pinned image ID required")
        host = "npipe:////./pipe/dockerDesktopLinuxEngine" if os.name == "nt" else "unix:///var/run/docker.sock"
        self.command = [docker, "--config", config, "--host", host]
        self.image, self.owner = image, owner
        self.controls = dict.fromkeys(MANDATORY, "IMPLEMENTED_NOT_VERIFIED")
        self.tests = {}
        self.eligible = False
        self.records = {}
        self.generation = 0
        self.lock = threading.Lock()
        self.pool = concurrent.futures.ThreadPoolExecutor(max_workers=1, thread_name_prefix="guest-supervisor")
        self.closed = False

    def cli(self, *args, timeout=5):
        return subprocess.run(self.command + list(args), stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                              timeout=timeout, check=True, env=self.clean_env())

    @staticmethod
    def clean_env():
        return {key: os.environ[key] for key in ("SystemRoot", "WINDIR") if key in os.environ}

    def remove(self, name):
        # Only this launch's unpredictable, generated names; never enumerate/delete unrelated containers.
        try:
            self.cli("rm", "--force", name)
        except subprocess.CalledProcessError:
            pass
        existing = self.cli("ps", "--all", "--filter", "name=^/" + name + "$", "--format", "{{.Names}}")
        if existing.stdout.strip():
            raise RuntimeError("Scope cleanup unconfirmed")

    def run_guest(self, source=None, observation=None, mode="--execute", cancelled=None, ready=None):
        cancelled = cancelled or threading.Event()
        name = "bc-8f-" + self.owner + "-" + uuid.uuid4().hex
        process = None
        result = {"failure": "RUNNER_UNAVAILABLE", "evidence": "SCOPE_STARTUP_FAILURE"}
        started = 0
        try:
            self.cli("create", "--name", name, "--label", "battlecode.worker=" + self.owner,
                     "--network", "none", "--read-only", "--cap-drop", "ALL",
                     "--security-opt", "no-new-privileges", "--pids-limit", "1",
                     "--memory", "128m", "--memory-swap", "128m", "--cpus", "1",
                     "--ulimit", "nofile=32:32", "--ulimit", "core=0:0",
                     "--user", "65534:65534", "--log-driver", "none", "-i", self.image, mode)
            info = decode(self.cli("inspect", name).stdout)[0]
            config = info["HostConfig"]
            if not (info["Image"] == self.image and config["ReadonlyRootfs"]
                    and config["NetworkMode"] == "none" and config["PidsLimit"] == 1
                    and config["Memory"] == 134217728 and config["MemorySwap"] == 134217728
                    and config["NanoCpus"] == 1000000000
                    and "ALL" in config["CapDrop"] and not config["Privileged"]
                    and not config["Binds"] and not info["Mounts"]
                    and config["PidMode"] != "host" and info["Config"]["User"] == "65534:65534"
                    and any(s.startswith("no-new-privileges") for s in config["SecurityOpt"])):
                raise RuntimeError("Scope policy mismatch")
            if cancelled.is_set():
                raise RuntimeError("Cancelled before scope start")
            process = subprocess.Popen(self.command + ["start", "--attach", "--interactive", name],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=self.clean_env())
            lines = queue.Queue(maxsize=4)
            def drain():
                try:
                    for _ in range(3):
                        line = process.stdout.readline(4097)
                        if not line:
                            break
                        if len(line) > 4096:
                            lines.put_nowait(None)
                            return
                        lines.put_nowait(decode(line))
                except Exception:
                    pass
                finally:
                    try:
                        lines.put_nowait(None)
                    except queue.Full:
                        pass
            reader = threading.Thread(target=drain, daemon=True)
            reader.start()
            first = lines.get(timeout=2)
            if mode == "--probe-os":
                result = first
            else:
                if first != {"stage": "READY"}:
                    raise RuntimeError("Bootstrap failure")
                started = time.monotonic()
                if mode == "--execute":
                    raw = json.dumps(dict(source=source, observation=observation), ensure_ascii=False,
                                     separators=(",", ":")).encode("utf-8")
                    process.stdin.write(struct.pack(">I", len(raw)) + raw)
                    process.stdin.flush()
                process.stdin.close()
                if ready is not None: ready.set()
                deadline = started + 1.6
                while True:
                    if cancelled.is_set():
                        result = {"failure": "MATCH_CANCELLED", "evidence": "CANCELLED"}
                        break
                    try:
                        reply = lines.get(timeout=0.025)
                        if reply is None:
                            state = decode(self.cli("inspect", name).stdout)[0]["State"]
                            result = ({"failure": "BOT_RESOURCE_LIMIT", "evidence": "CGROUP_OOM_KILL"}
                                      if state["OOMKilled"] else {"failure": "RUNNER_CRASH", "evidence": "UNKNOWN_GUEST_CRASH"})
                        else:
                            result = reply
                        break
                    except queue.Empty:
                        if time.monotonic() >= deadline:
                            result = {"failure": "INTERNAL_ERROR", "evidence": "SUPERVISOR_WALL_DEADLINE"}
                            break
        except Exception:
            result = {"failure": "MATCH_CANCELLED" if cancelled.is_set() else "RUNNER_CRASH",
                      "evidence": "CANCELLED" if cancelled.is_set() else "SCOPE_OR_TRANSPORT_FAILURE"}
        finally:
            try:
                self.remove(name)
            except Exception:
                self.eligible = False
                self.controls["cleanup"] = "UNAVAILABLE"
                result = {"failure": "RUNNER_CRASH", "evidence": "CLEANUP_UNCONFIRMED"}
            if process is not None:
                try:
                    process.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=2)
                process.stdout.close()
                if not process.stdin.closed:
                    process.stdin.close()
            if started:
                result["elapsedTimeMs"] = int((time.monotonic() - started) * 1000)
        return result

    def verify(self):
        """Live gate. All probes run the exact image/config; no cached JSON approval or caller override."""
        try:
            host = decode(self.cli("info", "--format", "{{json .}}").stdout)
            if host["OSType"] != "linux" or host["CgroupVersion"] != "2":
                raise RuntimeError("Linux cgroups v2 required")
            image = decode(self.cli("image", "inspect", self.image).stdout)[0]
            if image["Id"] != self.image:
                raise RuntimeError("Image mismatch")
            os_result = self.run_guest(mode="--probe-os")
            self.tests["os_controls"] = "FAIL"
            require(all(os_result.get(key) is True for key in ("os", "filesystem", "network", "process", "exec", "readonly", "nonroot", "pidNamespace", "fileDescriptors", "environment")))
            require(os_result["memory"] == 134217728 and os_result["swap"] == 0 and os_result["pids"] == 1)
            self.tests["os_controls"] = "PASS"
            for control in ("filesystem", "network", "subprocess", "nonroot", "readonlyRoot",
                            "pidNamespace", "processMemory", "fileDescriptors", "processCount", "imageIdentity", "cleanEnvironment"):
                self.controls[control] = "ENFORCED_AND_VERIFIED"
            observation = dict(apiVersion="bot-1", gameId="ott-board-9x9-v1", playerId="A", turn=1,
                boardSize=9, board=[], legalMoves=[dict(pieceId="A-R1", to=dict(row=1, col=0))], remainingTimeMs=1000)
            valid = "function chooseMove(o){return o.legalMoves[0];}"
            def check(name, source=None, failure=None, mode="--execute"):
                result = self.run_guest(source, observation, mode)
                good = (result.get("failure") == failure if failure else result.get("action") == observation["legalMoves"][0])
                self.tests[name] = "PASS" if good else "FAIL"
                if not good:
                    raise RuntimeError("Probe failed")
                return result
            check("valid", valid)
            check("invalid_action", "function chooseMove(o){return {bad:true};}", "INVALID_ACTION")
            check("accessor_action", "function chooseMove(o){return {get pieceId(){return 'A-R1'},to:{row:1,col:0}};}", "INVALID_ACTION")
            check("no_dynamic_eval", "function chooseMove(o){if(typeof eval!=='undefined'||typeof Function!=='undefined'||typeof (function*(){}).constructor!=='undefined')throw Error();return o.legalMoves[0];}")
            check("immutable_observation", "function chooseMove(o){o.legalMoves[0].pieceId='BAD';return o.legalMoves[0];}")
            check("syntax_error", "function chooseMove( {", "BOT_RUNTIME_ERROR")
            cpu = check("infinite_loop", "while(true){}", "BOT_TIMEOUT")
            require(cpu["evidence"] == "KERNEL_PROCESS_CPU_TIMER")
            self.controls["cpuTime"] = "ENFORCED_AND_VERIFIED"
            check("deep_recursion", "function f(){return f();}function chooseMove(o){return f();}", "BOT_RUNTIME_ERROR")
            self.controls["stack"] = "ENFORCED_AND_VERIFIED"
            heap = check("heap_exhaustion", "function chooseMove(o){return new Uint8Array(67108864);}", "BOT_RESOURCE_LIMIT")
            require(heap["evidence"] == "NATIVE_HEAP_CAP")
            self.controls["heap"] = "ENFORCED_AND_VERIFIED"
            memory = check("process_memory_exhaustion", failure="BOT_RESOURCE_LIMIT", mode="--probe-memory")
            require(memory["evidence"] == "CGROUP_OOM_KILL")
            check("oversized_output", "function chooseMove(o){return {pieceId:'x'.repeat(4096),to:{row:1,col:0}};}", "BOT_RESOURCE_LIMIT")
            self.controls["output"] = "ENFORCED_AND_VERIFIED"
            wall = check("wall_watchdog", failure="INTERNAL_ERROR", mode="--probe-wall")
            require(wall["evidence"] == "WALL_EXPIRED_WITHOUT_CPU_PROOF")
            self.controls["wallTime"] = "ENFORCED_AND_VERIFIED"
            for name, source in (
                ("filesystem_api", "function chooseMove(o){return std.loadFile('/etc/shadow');}"),
                ("network_api", "function chooseMove(o){return fetch('http://example.invalid');}"),
                ("subprocess_api", "function chooseMove(o){return os.exec(['/bin/sh']);}")):
                check(name, source, "BOT_RUNTIME_ERROR")
            module = self.run_guest("function chooseMove(o){return import('secret');}", observation)
            require(module.get("failure") in ("BOT_RUNTIME_ERROR", "INVALID_ACTION"))
            self.tests["dynamic_import"] = "PASS"
            self.controls["moduleDenial"] = "ENFORCED_AND_VERIFIED"
            fresh = "globalThis.count=(globalThis.count||0)+1;function chooseMove(o){if(count!==1)throw Error();return o.legalMoves[0];}"
            check("fresh_context_1", fresh);check("fresh_context_2", fresh)
            self.controls["freshContext"] = "ENFORCED_AND_VERIFIED"
            cancel = threading.Event()
            ready = threading.Event()
            def cancel_started():
                if ready.wait(5):
                    time.sleep(0.1); cancel.set()
            timer = threading.Thread(target=cancel_started);timer.start()
            cancelled = self.run_guest(mode="--probe-wall", cancelled=cancel, ready=ready)
            timer.join(timeout=6)
            require(ready.is_set() and not timer.is_alive())
            require(cancelled["failure"] == "MATCH_CANCELLED")
            self.tests["cancellation"] = "PASS"
            require(not self.cli("ps", "--all", "--filter", "label=battlecode.worker=" + self.owner, "--format", "{{.ID}}").stdout.strip())
            self.tests["cleanup"] = "PASS"
            self.controls["cleanup"] = "ENFORCED_AND_VERIFIED"
            self.eligible = all(v == "ENFORCED_AND_VERIFIED" for v in self.controls.values())
        except Exception:
            self.eligible = False
            self.tests["gate"] = "FAIL"
        return dict(executable=self.eligible, controls=self.controls, tests=self.tests,
                    runtimeFingerprint="linux-quickjs-ng-0.16.2/" + self.image, workerEpoch=self.owner)

    def execute(self, request, reply):
        execution = request["executionId"]
        fingerprint = hashlib.sha256(json.dumps(request, sort_keys=True).encode()).hexdigest()
        with self.lock:
            generation = request.get("generation", 0)
            if type(generation) is not int or generation < self.generation:
                reply(dict(executionId=execution, failure="INTERNAL_ERROR", evidence="STALE_MATCH_GENERATION"))
                return
            if generation > self.generation:
                if any(not job["future"].done() for job in self.records.values()):
                    reply(dict(executionId=execution, failure="RUNNER_UNAVAILABLE", evidence="SLOT_BUSY"))
                    return
                self.records.clear()
                self.generation = generation
            if execution in self.records:
                old = self.records[execution]
                if old["fingerprint"] != fingerprint:
                    reply(dict(executionId=execution, failure="INTERNAL_ERROR", evidence="ID_CONFLICT"))
                else:
                    old["future"].add_done_callback(lambda f: reply(dict(executionId=execution, **f.result())))
                return
            if self.closed or not self.eligible or len(self.records) >= 4096:
                reply(dict(executionId=execution, failure="RUNNER_UNAVAILABLE", evidence="GATE_OR_CAPACITY"))
                return
            if any(not job["future"].done() for job in self.records.values()):
                reply(dict(executionId=execution, failure="RUNNER_UNAVAILABLE", evidence="SLOT_BUSY"))
                return
            try:
                require(request["limits"] == PROFILE)
                submission = request["submission"]
                require(set(submission) == {"submissionId", "version", "sourceSha256", "sourceBase64"})
                uuid.UUID(submission["submissionId"])
                require(type(submission["version"]) is int and submission["version"] > 0)
                source = base64.b64decode(submission["sourceBase64"], validate=True)
                require(0 < len(source) <= 32768 and hashlib.sha256(source).hexdigest() == submission["sourceSha256"])
                source = source.decode("utf-8", "strict")
                observation = request["observation"]
                require(observation["apiVersion"] == "bot-1" and observation["gameId"] == "ott-board-9x9-v1")
                require(observation["remainingTimeMs"] == 1000)
                require(len(json.dumps(observation).encode()) <= 16384)
            except Exception:
                reply(dict(executionId=execution, failure="INTERNAL_ERROR", evidence="REQUEST_INTEGRITY"))
                return
            cancel = threading.Event()
            future = self.pool.submit(self.run_guest, source, observation, "--execute", cancel)
            self.records[execution] = dict(fingerprint=fingerprint, future=future, cancel=cancel)
            future.add_done_callback(lambda f: reply(dict(executionId=execution, **f.result())))

    def cancel(self, execution):
        with self.lock:
            job = self.records.get(execution)
            if job:
                job["cancel"].set()

    def close(self):
        with self.lock:
            self.closed = True
            for job in self.records.values():
                job["cancel"].set()
        self.pool.shutdown(wait=True, cancel_futures=False)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--docker", required=True)
    parser.add_argument("--image", required=True)
    parser.add_argument("--owner", required=True)
    parser.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-f0-9]{32}", args.owner):
        raise ValueError("Owner")
    lock = threading.Lock()
    token = None
    def reply(value):
        raw = json.dumps(dict(protocolVersion="runner-1", auth=token, **value), separators=(",", ":")).encode()
        if len(raw) > 8192:
            raise ValueError("Response cap")
        with lock:
            sys.stdout.buffer.write(struct.pack(">I", len(raw)) + raw)
            sys.stdout.buffer.flush()
    with tempfile.TemporaryDirectory(prefix="battlecode-worker-config-") as config:
        worker = Supervisor(args.docker, args.image, args.owner, config)
        try:
            if args.verify:
                report=worker.verify()
                print(json.dumps(report,indent=2))
                return 0 if report["executable"] else 3
            hello = read_frame(sys.stdin.buffer)
            if set(hello) != {"protocolVersion", "op", "auth"} or hello["protocolVersion"] != "runner-1" or hello["op"] != "HELLO" or not re.fullmatch(r"[a-f0-9]{64}", hello["auth"]):
                raise ValueError("Authentication")
            token = hello["auth"]
            reply(dict(op="CAPABILITIES", **worker.verify()))
            while True:
                request = read_frame(sys.stdin.buffer)
                if request is None:
                    break
                if request["protocolVersion"] != "runner-1" or not hmac.compare_digest(token, request["auth"]):
                    raise ValueError("Authentication")
                op = request["op"]
                if op == "EXECUTE" and set(request) in ({"protocolVersion", "op", "auth", "executionId", "submission", "observation", "limits"},
                    {"protocolVersion", "op", "auth", "executionId", "submission", "observation", "limits", "generation"}):
                    uuid.UUID(request["executionId"])
                    worker.execute(request, reply)
                elif op == "CANCEL" and set(request) == {"protocolVersion", "op", "auth", "executionId"}:
                    worker.cancel(request["executionId"])
                else:
                    raise ValueError("Protocol")
        finally:
            worker.close()

if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception:
        # No untrusted diagnostics/source, paths or credentials.
        sys.exit(2)
