# Phase 8F.2 — resources and live verification

Admission requires all 19 controls to be `ENFORCED_AND_VERIFIED` by a live preflight of the exact image/configuration. Missing, unsupported, failed or incomplete checks leave user bots `NOT_EXECUTABLE`. No allowUnsafe flag, cached report approval or prototype fallback exists. Checks use explicit conditions that remain active under Python optimization.

| Control | Enforcement and real verification |
|---|---|
| filesystem | Native seccomp denies open/openat; actual openat syscall returns EPERM |
| network | Network namespace `none`; actual socket syscall returns EPERM |
| subprocess | PID cap 1 and native seccomp; actual fork and execve return EPERM |
| nonroot | UID/GID 65534; native effective UID check |
| readonlyRoot | Docker read-only root; native statvfs ST_RDONLY |
| pidNamespace | Private namespace, guest PID 1, host PID mode rejected |
| processMemory | cgroups v2 memory.max 128MiB, swap.max 0; native 256MiB allocation/touch produces proven cgroup OOM kill |
| cpuTime | Kernel process CPU timer 250ms; JavaScript infinite loop terminated with trusted native timer evidence |
| wallTime | Kernel monotonic timer 1000ms plus supervisor 1600ms watchdog; native blocking probe expires |
| heap | Native QuickJS allocator cap 32MiB, proven denied allocation |
| stack | QuickJS 1MiB stack cap, real deep recursion fails |
| output | Native strict BotAction primitive serialization ≤1024 bytes, oversize rejected |
| fileDescriptors | Hard/soft RLIMIT_NOFILE 32; actual dup exhaustion returns EMFILE |
| processCount | cgroups v2 pids.max 1; no children allowed |
| freshContext | New OS scope/runtime/context every turn; globals do not survive two real runs |
| moduleDenial | No filesystem or host modules; dynamic import returns no valid action |
| cleanup | Container absent after completion, timeout and cancellation |
| imageIdentity | Exact image inspect/created container identity compared with pinned ID |
| cleanEnvironment | Guest clearenv; native empty environment check |

All 19 were verified on this machine; [recorded live evidence](phase8f-capabilities.json) contains exact statuses and image identity. The live guest suite contains 21 checks including actual OS syscalls, process OOM, recursion, malformed actions/accessors, immutable observation, dynamic constructors and cancellation after a running guest signals READY. No OS control is labeled verified solely because a JavaScript API is missing.

Process startup has a separate bounded bootstrap deadline. The 1000ms wall and 250ms CPU budgets cover input parse, compilation and chooseMove after input arrival. Docker scheduling/cleanup are not charged as bot CPU. Reported elapsedTimeMs is supervisor time from READY through cleanup. Guest source is ≤32KiB UTF-8; observation ≤16KiB; stack 1MiB; descriptors 32; source never enters diagnostic logs.

`BotExecutionService` retains its one active/four queued bounds and queue/delegate deadlines. Supervisor admits one active scope, max 4096 execution records per worker lifetime. Arena permits one active match and 16 retained matches; submissions retain existing 32 bots/16 versions/1024 request records. Limits are identical for both sides. Capacity/unknown startup/crash/host deadline produce infrastructure cancellation, not a bot loss.

Only trusted native evidence allows BOT_TIMEOUT/BOT_RESOURCE_LIMIT. Kernel wall expiry without CPU proof is INTERNAL_ERROR because host starvation is not proof of bot misbehavior. Proven invalid action/runtime error/CPU or resource failure → existing coordinator FORFEIT. Unknown crash, lost supervisor, backend timeout, unavailable runner and shutdown → CANCELLED. Session/requestId/expectedTurn/out-of-turn validation remains unchanged.

References: [Docker resource/isolation options](https://docs.docker.com/engine/containers/run/), [seccomp](https://docs.docker.com/engine/security/seccomp/), [Linux cgroups v2](https://www.kernel.org/doc/html/latest/admin-guide/cgroup-v2.html). Default Docker isolation alone is not the boundary; the guest installs an additional default-deny native seccomp filter and uses cgroup/timer/resource checks.
