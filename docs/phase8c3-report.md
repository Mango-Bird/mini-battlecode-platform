# Phase 8C.3 — Trusted external JavaScript prototype

2026-10-13. Status: executable `TRUSTED_FIXTURES_ONLY`; NOT a secure sandbox.

## Runtime actually used

Official QuickJS-NG0.16.2 Windows x64 executable was downloaded and executed. SHA-256: `7b27412de844403545bd151fbe49191b4d5b91a9e15b5db7c863fea54639a82b`. The runner checks that artifact at construction and rejects unverified/missing executables. `scripts/setup-quickjs.ps1` downloads and verifies the pinned artifact; binary/cache/logs stay ignored under `.tools/`. [Official installation/release source](https://quickjs-ng.github.io/quickjs/installation/).

`TrustedBotFixtures` admits only exact hash+bytes of the reviewed bundled scripts. Arbitrary source remains disabled, including when a caller supplies a valid SHA-256. Changing fixture resources is a trusted-code change, not a player submission mechanism.

Each execution starts a fresh external QuickJS process and fresh temporary directory. The fixture is a separate module from the trusted control module; chooseMove receives only a deep-frozen BotObservation, with no std/os bindings or supervisor credentials provided as API. Script bytes go to a file, observation goes through pipes; no shell and no source interpolation into shell arguments. Environment is cleared except Windows SystemRoot. The CLI module loader and filesystem access are still present in the process: this is NOT OS capability denial, and is why arbitrary code stays disabled.

## Control protocol actually implemented

Private pipes use4-byte big-endian length+JSON. Request cap131072 bytes, actual observation frame cap16384; response cap4096; action cap1024. Reads handle fragmented/truncated frames and validate length before allocation. Control STARTED→GO handshake separates bootstrap from guest invocation. IDs must correlate; unexpected/trailing frames, EOF and unclassified exits are infrastructure. This is a local prototype control protocol, not the full proposed remote runner-1 service.

Bootstrap includes module parsing/compilation/initialization and has a2-second deadline. The1000ms guest window starts immediately before GO; it covers chooseMove and output extraction. Compilation is not yet charged to the guest budget—an explicit prototype limitation before arbitrary source support. Output checks plain own data properties, exact fields, integer coordinates and Promise/thenable rejection; backend independently parses BotAction.

## Enforced vs unproven

| Limit/control | Actual status |
|---|---|
| Source32KiB, action1024, frame/depth bounds | Enforced in Java/control code, covered by tests |
| Guest wall1000ms and cancellation | Parent kills process after observed GO deadline; loop/cancel tests pass |
| Bot timeout evidence | Require observed guest process CPU delta>=250ms at wall expiry; absent/insufficient evidence is INTERNAL_ERROR, hence cancellation |
| JS heap32MiB / stack1MiB | QuickJS CLI flags accepted and supplied; no allocation/stack stress proof yet |
| CPU250ms quota | NOT enforced at250ms; CPU accounting is attribution evidence after wall expiry, not a hard scheduling quota |
| Process RAM128MiB | NOT enforced; no verified Job Object/cgroup |
| OS filesystem/network/process denial | NOT verified; CLI loader remains capable; only reviewed fixtures allowed |
| AppContainer/Job Objects / Wasmtime | No helper/runtime found or isolation proof performed |
| Process tree/temp/thread cleanup | Tested on reviewed fixtures; failed cleanup quarantines runner and rejects further work; not race-safe OS containment proof |

Unknown process crashes/host OOM remain infrastructure. No Node vm/eval, no Java JavaScript evaluator and no Render execution. The capability report explicitly says osIsolationVerified/cpuQuota/processMemoryQuota=false. Generic wall expiry without CPU evidence never assigns a loser.

## Tests and samples

FirstLegalMoveBot selects the first legal move. CapturePriorityBot prefers a legal destination containing an enemy; fallback first legal move. Both use bot-1/ott-board-9x9-v1. Reviewed fault fixtures cover invalid action, missing chooseMove, runtime exception, Promise, infinite loop and fresh global context.

12 real-JS tests passed, including capture priority,3 independent context executions, cancellation, arbitrary-source rejection and unavailable runtime.4 framing tests passed. Missing QuickJS fails tests visibly; there is no assumption/skip/fake fallback. Real-JS match/replay evidence is in [8C.4](phase8c4-report.md).

Before public submissions: implement and independently validate OS isolation, hard CPU/process-memory limits, stripped capabilities/dynamic-code restrictions, guest compilation budget, robust resource evidence and security/failure/load review. None of these is claimed complete here.
