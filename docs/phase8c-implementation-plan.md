# Phase 8C implementation plan — design only

2026-10-13. Không bắt đầu8C.2; mọi contracts/new modules/hooks dưới đây là đề xuất. Giữ Phase1–8B functionality và frozen TCP1.0/web-1/bot-1.

## Approval gates

| Decision | Recommendation |
|---|---|
| Local isolation | AppContainer+Job Objects+stripped native QuickJS in external process; fake/trusted-only until proven |
| Production isolation/hosting | QuickJS-Wasm+Wasmtime external Linux worker with OS layer; no hostile bots on Render Free |
| Resource profiles | Candidate numbers in execution protocol, calibrate before freeze; native vs Wasm separate fingerprints |
| Error adjudication | Proven bot fault FORFEIT; infrastructure CANCELLED, no pause or automatic rerun |
| Manager integration | New internal BotMatchAccess, latest committed state refs, cause-aware BOT watchdog; existing public interfaces untouched unless separate approval |
| Identity/ownership | Server-owned seats bound to authorized frozen human submissions; viewer disconnect separate from manual player disconnect |
| JavaScript policy | Sync script subset, no dynamic code/imports/Promise/host capabilities/time/random; approve exact supported subset |
| Ingress/source retention | No new TCP/WS commands now; trusted fixture catalog first, later authenticated submission API requires separate contract |
| Replay/audit | In-memory initial manifest+validated events+failure evidence; persistence/export/source visibility separately approved |

## Phase8C.2 — Runner interface and fake tests

Add minimal proposed bot-runner contracts/DTOs, immutable source/version/hash checks and backend adapter lifecycle. Fake runner only; no interpreter binaries/process execution. Validate profile/frame contracts and strict schema via existing/test tooling without changing network protocols. Fake time/admission/cancellation tests deterministic; no real sleeps to simulate budgets.

Acceptance: exact result union, hash freeze/no leaks, bounded outstanding/queue/records, duplicate-ID same body executes once, conflict ID rejects, expired request after tombstone retention and old Runner epoch reject without execution, clock skew is infrastructure, cancel/later success stable, infrastructure versus bot failure matrix, timeout distinction, output/schema/null/extra/float rejection. Run focused tests then full Maven reactor (baseline244 tests), no skip. Public API/module/dependency proposal finalized before code. No coordinator live integration or submission frontend.

## Phase8C.3 — Isolated JS prototype

Build pinned stripped QuickJS runtime/harness and trusted supervisor off-backend. First prove Windows isolation/Job resource controls, then Wasm build with explicit import allowlist/fuel/memory/stack; no assumption plain Wasmtime CLI or qjs CLI is enough. Load source, invoke sync chooseMove, native data-only extraction; empty observation mutation cannot affect Java. Supervisor protocol and process tree teardown tested through Java21 adapter.

Acceptance: truthful capability/limit attestation, unsupported language errors documented; endless top-level/function loops, recursion/memory/output bombs, Promise/getter/Proxy/dynamic import/eval indirect constructor/FS/network/subprocess probes denied or fail safely. Fresh context and no global state across turns. Measure cold-start and CPU/RSS profiles before fixing numbers. No enabling hostile public submissions; no Vercel/Render deploy.

## Phase8C.4 — Bot-vs-Bot coordinator

Implement only after BotMatchAccess/identity/deadline decisions approved. Existing manager owns lifecycle and same engine; freeze catalog submissions into A/B seats; atomic typed observation lease from latest committed state, outside-lock execute, guarded commit. Manual actions cannot control server-owned seats. Add generic bridge without competition→rules-impl dependency. Cause-aware BOT scheduler preserves manual behavior; infrastructure termination uses manager cleanup/notifications.

Acceptance: A/B match completes via actual OTT rule; valid history replay matches board, goals/elimination/draw/forfeit retained. expectedTurn0 vs observation1 correct. Source/version/profile stable; active side sees own source only. Races success/cancel/timeout/disconnect/shutdown/duplicate/stale Runner epoch handled exactly once. Queue/setup/host stall never becomes BOT_TIMEOUT merely because engine deadline expired. Failure after last committed result cannot overwrite winner; loss of Runner no fake disconnect. No second engine or reflection/private state access. All244 Phase8B regressions retained; no game-rule/public interface breakage without renewed approval.

## Phase8C.5 — Security/performance/failure validation

Fault inject queue overflow, partial pipe frames, poisoned result ID/hash, worker kill/restart/OOM, backend restart, missing isolation capability, slow stdout/stderr, network partition, host starvation, forced-kill failure. Bounded load profiles initially1 local/2 production executions, then explicit larger measured tests only. Capture p50/p95 CPU/wall/startup/RSS, process/fd counts and at least1000 sequential cleanup executions on selected OS.

Acceptance: cancellation/failure latency bounded, no threads/process trees/handles/temp storage left; backend responsiveness under overload; no innocent bot forfeits; no opponent/source/credential exposure; independent security review and runtime patch policy before public exposure. Wasm fingerprint same within match, deterministic replay digest across repeated board replays; code re-execution reproducibility claimed only for pinned tested profile and deterministic features. Latency/SLA/Render suitability cannot be inferred from local tests.

## Verification and boundaries

At each implementation phase run focused tests and full mvn clean test/package without skip, aggregate per-module counts. Frontend tests/build only if authorized frontend changes later. No browser multiplayer claim without real browser test. Submission UI/API, tournament, persisted replay, rating, resume and deployment remain separately scoped; Phase8C.1 creates docs only. Decisions not answered mean subsequent phase does not silently select security-sensitive defaults.
