# Phase 8C.1 — Sandbox security and deployment proposal

Design only, not a tested security claim. Không JavaScript eval/Node vm trong Java backend; không coi AST blacklist, Node permissions hoặc mặc định Docker là security sandbox.

## Threat model

Attacker là player kiểm soát source, input size và bot return value. Assets: backend credentials/session metadata, opponent source, room/state integrity, availability, host filesystem/network, other tenants. Trust boundary: backend→supervisor authenticated; supervisor→disposable guest restricted. Supervisor/harness/runtime images là trusted nhưng có thể có bugs/CVEs; source/guest output/diagnostics untrusted. Runner transport needs integrity/authentication: a malicious compromised supervisor can forge evidence, so it is in trusted computing base, never a player-hosted endpoint.

| Threat | Required control / residual risk |
|---|---|
| infinite loop/top-level loop/recursion | fuel/CPU/wall limits covering load, execution and extraction; external kill watchdog |
| memory/string/object/stack bomb | guest heap + entire process + stack caps, host reserve; resource proof vs host OOM distinguished |
| import/FS/SSRF/process/native modules | no JS host bindings/import loader/WASI sockets/preopened dirs; OS deny network/process creation and restricted filesystem |
| sandbox escape | patched runtime, separate process/user, OS/Wasm layers; no absolute escape guarantee |
| output/prototype/getter/Proxy tricks | native trusted descriptor checks; plain data-only action, capped serialization; guest traps remain within fuel/time budget |
| source/identity leakage | own source only, clean env/handles, no host mounts/opponent cache readable by guest, sanitized bounded logs |
| forged Runner messages | guest cannot access supervisor control pipe/service credentials; correlated strict frames and duplicate cache |
| queue/fork/log bomb | bounded active/pending/records, PID/process count cap, pipe drain with cap; no shell commands from source |
| late reply/restart/duplicate action | manager lease expectedTurn+generation+epoch, at-most-once terminal/commit |
| telemetry side channels | deny live clock/random imports, dedicated/limited worker pool; microarchitectural timing leakage not fully eliminated |
| vulnerable dependency/build artifact | pinned image/runtime/JS engine hashes, SBOM/advisory review, signed/verified trusted artifact |

## Comparison

| Criterion | A. Separate OS process + OS isolation | B. Hardened container sandbox | C. Wasm runtime + trusted JS interpreter |
|---|---|---|---|
| Boundary | Own address space plus AppContainer/restricted token or Linux namespaces/seccomp | OS namespaces/cgroups; ordinarily shared host kernel | Wasm memory/control-flow/import boundary; runtime bugs still possible |
| Escape resistance | Bare process alone insufficient; reduce OS rights/syscalls | Default Docker insufficient; rootless/nonroot/drop caps/seccomp + gVisor/microVM improve | No syscalls except imports; denied capabilities by construction, add OS process layer |
| CPU/time | Job/cgroups quotas and monotonic parent watchdog; scheduler delays matter | cgroup CPU/accounting + external wall deadline | Fuel/epoch interruption + process CPU/wall; Wasm fuel is not JS operation count |
| RAM | process/job limits + interpreter heap/stack | hard memory/PID/tmpfs caps, account daemon/kernel too | linear-memory/table/stack limits plus host/process memory (JIT/cache overhead not linear memory) |
| Network/FS | deny capabilities/ACL/syscalls; clean env/handles | network none, read-only root, bounded tmpfs, no host mounts/socket | no WASI or explicit empty capability set; no networking/filesystem imports |
| Cold start | new interpreter process each turn; measure | per-execution container slower; cached immutable image only | precompiled trusted interpreter possible; fresh instance each turn; measure compilation/startup |
| Windows11 dev | AppContainer+Job Objects native helper; ProcessBuilder alone insufficient | Linux container requires WSL2/Docker/VM availability; not assumed | Wasmtime process + interpreter guest feasible candidate; QuickJS Wasm build/API harness must be proven |
| Java21 integration | ProcessBuilder talks to trusted supervisor; OS native helper outside JVM | service/CLI supervisor, never Docker daemon granted to Java clients | external executable/service protocol, no Wasm engine JNI required in backend |
| Render Free | process limit/isolation rights not established; no approved hostile-code profile | ability to run own service image does not prove nested sandbox/daemon/cgroup permissions | theoretically small workers, but CPU/RAM/capability/kill evidence unverified; not approved |
| Cost/complexity | low infrastructure, Windows native policy work and lifecycle complexity | moderate/high operational work; stronger isolation needs own capable host | interpreter/Wasm integration and resource tuning high initial work; no assumed direct JS support |

Wasmtime executes Wasm, not arbitrary JS source. Option C requires trusted QuickJS compiled as guest + custom host/harness; do not accept player-supplied Wasm binaries. Pure JS engine builds exclude std/os/module/native loaders. Any QuickJS source evaluation happens only inside isolated child/guest, not as a sandbox in Java. No default qjs CLI with standard library for hostile bots. QuickJS documents heap/stack/interrupt controls and optional features; they are resource mechanisms, not OS isolation. [QuickJS](https://bellard.org/quickjs/quickjs.html).

Microsoft documents AppContainer resource isolation and Job Objects process-group resource management. These are separate concerns: Job Objects alone do not deny filesystem/network. [AppContainer](https://learn.microsoft.com/en-us/windows/win32/secauthz/appcontainer-isolation), [Job Objects](https://learn.microsoft.com/en-us/windows/win32/procthread/job-objects).

Docker daemon privileges and container/kernel boundary need explicit threat review; prohibit privileged/host-network/host-PID/host mounts/Docker socket. [Docker security](https://docs.docker.com/engine/security/). gVisor adds a userspace kernel layer, with compatibility/performance constraints; it is not a promise of zero escapes. [gVisor](https://gvisor.dev/docs/architecture_guide/intro/).

Wasm access to host is through explicit imports; WASI is capability-based. Fuel and epoch interruption address runaway execution, and process-level bounds remain necessary. [Wasmtime security](https://docs.wasmtime.dev/security.html), [interrupting execution](https://docs.wasmtime.dev/examples-interrupting-wasm.html). Node explicitly disclaims vm as a security mechanism and permissions as protection against malicious code; neither is chosen as isolation. [Node vm](https://github.com/nodejs/node/blob/main/doc/api/vm.md), [permissions](https://nodejs.org/download/release/v26.5.1/docs/api/permissions.html).

## Recommendations (pending approval)

Local Windows11: A, trusted supervisor/native helper starts stripped QuickJS child inside AppContainer with no network capabilities and minimal read access to trusted runtime files; Job Object memory/CPU/process limit, no breakaway, kill-on-close. Handles allowlisted, source/observation pipe-fed, no guest subprocess. Attach isolation/limits before untrusted source loads; create suspended if required, then assign job and resume. Without proven helper/setup, allow only fake Runner/trusted samples; fail closed for arbitrary submissions. No Docker Desktop assumption.

Production: C layered with A on a dedicated Linux worker host/VM separate from backend. Trusted QuickJS-Wasm interpreter in external Wasmtime process, fresh guest per execution; no WASI/imports for FS/network/clocks/random/process. Fuel + memory/stack caps and external process cgroup/seccomp watchdog. Supervisor has authenticated service networking; child has none. Add hardened container/gVisor only when host supports the necessary controls and tests. Initial planning target>=1GiB worker memory and controlled CPU capacity for2 slots, not a measured minimum or free-host guarantee. Per-match runtime fingerprint must not mix native local and Wasm production profiles.

No technology selected here has been installed/benchmarked/security-tested. Exact runtime release/build digest/licensing, native helper approach and capabilities need approval/prototype. Root/cgroup namespace assumptions on a hosting platform require evidence, not merely Dockerfile success.

## Source and JavaScript policy

Strict UTF-8<=32KiB. Backend authorizes immutable submission version/hash, AST parses in a bounded validation service, rejects syntax and prohibited constructs before match. Proposed synchronous script subset with named function chooseMove(observation), helpers/loops/data structures; reject import/export/dynamic import, async/await/Promise, eval/Function code generation and host APIs. AST screening is policy feedback, not escape prevention; runtime must actually omit capabilities and code-generation routes, including indirect constructors. Promise/proxy/eval feature removal needs build proof. Unsupported semantics must be documented, never silently rewritten.

Fresh context per turn, source executed from frozen bytes, observation copied/deep-frozen in guest; attempts to mutate cannot touch backend. Native harness uses trusted data-property extraction, not bot-overridable JSON.stringify/toJSON. Reject accessors/Proxy/thenables/arrays/symbol/extra fields as action; any traps run within guest resource budget. Strict backend JSON parser independently validates serialized output, then BotAction.decode and final rule legality.

Do not load user compiled bytecode/artifacts: unsafe runtime-specific bytecode is not a trusted format. No external libraries/downloads. Source validation may use a separate bounded compile sandbox; compiling hostile source on backend without resource limits is not acceptable. Service diagnostics sanitize control characters and never expose paths/source/tokens to browser.

V1 disallows nondeterministic random/time; seeded randomness is a future policy decision, not new bot-1 fields now. Fixed runtime/profile per match, no persistent guest state/cache. Trusted immutable compiled-interpreter caches are allowed with fingerprint checks; no cross-bot mutable JS heap or accessible source caches.

## Deployment constraints

Render Free currently512MB/0.1CPU, idle spin-down and restart lose in-memory state; Web Service exposes HTTP/WS on one public port. [Compute plans](https://render.com/docs/compute-plans), [Free limitations](https://render.com/docs/free), [Web Service](https://render.com/docs/web-services). Render Docker support means hosting a service image, not documented access to a safe nested per-player sandbox. [Render Docker](https://render.com/docs/docker).

Conclusion of this design: Render Free remains backend/WS demo hosting; untrusted code execution disabled there. This is a conservative suitability decision, not a claim that Render explicitly forbids Wasm or subprocesses. External authenticated worker is required for production proposal; its cost is separate and zero-cost capacity is not guaranteed. A separate Render Free Runner service still lacks proven isolation/performance/availability and is not endorsed. Vercel remains frontend only; no JS bot executed by client/browser as match authority.

Cold start/queue/host failure classified infrastructure → cancel, not bot loss. Backend restart cancels/loss of in-memory matches; Runner restart cancels affected matches. No pause/resume/persistence or deployment changes in Phase8C.1.

## Security acceptance gate

Before hostile submissions: capability probe fails every FS/network/process/import attempt; limit evidence for loop/stack/heap/output bombs; parent remains alive after child crashes; partial framing and forged envelope rejected; PID/memory/fd cleanup after1000 executions measured; no artifacts/secrets/source leakage between turns; concurrency overload bounded; runtime CVE review and patch/update procedure; required isolation failure is fail-closed. Independent review required before public multi-tenant use; prototype success is not a sandbox security certification.
