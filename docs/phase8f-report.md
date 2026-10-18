# Phase 8F — secure local user bot execution

Implemented, local Windows 11 / Docker Desktop WSL2 Linux engine. User bots may execute only through the live-verified OS worker. No arbitrary JavaScript executes in JVM/browser or the Phase 8C Windows fixture prototype. No commit, push, deployment, production sandbox or subsequent phase was performed.

## Delivered

- Isolated static QuickJS guest, authenticated external Python supervisor and Java adapter; per-turn fresh scope, cleanup, cancellation and fail-closed capability gate.
- Frozen saved botId/version/SHA-256/UTF-8 bytes → existing BotMatchCoordinator/MatchManager/GameEngine/OTT rule.
- Local source ownership, public metadata-only bot/version catalog, eligibility API and version-pinned battle POST.
- Workspace eligibility and arena selection of saved versions; real backend live state, results and replay.
- Real Docker security/failure/concurrency/hash/privacy/replay tests and reproducible scripts.

Details: [worker](phase8f1-report.md), [resource/control evidence](phase8f2-report.md), [submission/API](phase8f3-report.md), [frontend](phase8f4-report.md).

## Changed files

New `isolated-worker/{Dockerfile,guest.c,worker.py,test_worker.py,.gitignore}`; `scripts/{build-isolated-worker,run-secure-local-arena,test-isolated-worker}.ps1`; `bot-runner/.../VerifiedWorkerRunner.java`, `VerifiedWorkerGateTest.java`, `VerifiedWorkerSecurityIT.java`; `bot-match/.../LocalWorkspaceSessions.java`, `SecureLocalArenaMain.java`, `LocalSandboxGateTest.java`, `SavedBotBattleSecurityIT.java`; five phase reports and sanitized capability evidence JSON.

Updated `bot-match/.../{LocalSubmissionStore,LocalArenaService,LocalArenaHttpServer}.java`, `frontend/src/{local-submissions.ts,BotWorkspace.tsx,BotArena.tsx,BotWorkspace.test.tsx,BotArena.test.tsx}`, README. Existing unrelated working-tree changes from earlier phases were preserved. No phase-8F change to production ServerMain, engine, match manager, game rules, TCP/web-1 schema, Maven dependencies or deployment configuration.

## Run locally (PowerShell)

Prerequisites: Java 21 + Maven, Python 3 (tested 3.14.7), Docker Desktop Linux engine with WSL2/cgroups v2. Docker must actually be running; scripts do not assume availability or install/start it automatically. Internet is needed only to retrieve/build the pinned guest dependencies initially.

From the repository root:

```powershell
Set-Location D:\Socket\BattleCode
.\scripts\setup-quickjs.ps1
.\scripts\build-isolated-worker.ps1
mvn -Plocal-bots -pl bot-match -am package
if ($LASTEXITCODE -ne 0) { throw 'Maven failed' }
.\scripts\run-secure-local-arena.ps1 `
  -Python 'C:\Users\Admin\miniconda3\python.exe' `
  -Docker 'C:\Users\Admin\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe'
```

The Windows QuickJS binary is still required for the two existing fixture-only battles; saved or mixed battles use the isolated Linux guest for **both** sides. Startup runs live preflight and prints EXECUTABLE only when every mandatory control is verified. API binds only 127.0.0.1:8090; default allowed Origins are http://127.0.0.1:5173 and http://localhost:5173. Ctrl+C triggers graceful cancellation/cleanup.

In a second PowerShell window, including the previously missing pnpm/node PATH fix:

```powershell
Set-Location D:\Socket\BattleCode\frontend
$runtimeRoot = 'C:\Users\Admin\.cache\codex-runtimes\codex-primary-runtime\dependencies'
$env:Path = "$runtimeRoot\node\bin;$runtimeRoot\bin\fallback;$env:Path"
pnpm.cmd install --frozen-lockfile
pnpm.cmd exec vite --host 127.0.0.1 --port 5173 --strictPort
```

Open http://127.0.0.1:5173/bot-workspace, save two chooseMove bots, then select their saved versions at /bot-arena. `VITE_BOT_API_URL` may override the local loopback API for DEV only. It cannot point to an Internet backend. Exact paths above match this workstation; installations elsewhere must supply their own Python/Docker/runtime paths.

## Verification commands and evidence

```powershell
Set-Location D:\Socket\BattleCode
mvn clean test
mvn package
.\scripts\test-isolated-worker.ps1 `
  -Python 'C:\Users\Admin\miniconda3\python.exe' `
  -Docker 'C:\Users\Admin\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe'
```

Security suites are explicit `*SecurityIT` tests invoked by the script. They require a real Docker engine/image, fail when missing, and contain no skip/assumption paths. They are separate from ordinary Maven discovery, and were actually executed. Python tests verify hash/version tampering, duplicate/conflicting IDs, running-guest cancellation and cleanup, with the same live gate used by runtime.

The JUnit real-worker suite checks all live controls, fresh contexts, invalid/blank actions, syntax error, infinite loop, heap exhaustion, concurrent rejection, cancellation/recovery and actual supervisor crash. The real HTTP battle suite checks two independent owners, private source denial, frozen old version while saving a new one during RUNNING, duplicate/busy starts, real FORFEIT, authoritative replay, hash tampering, shutdown and actual crash → CANCELLED/eligibility revocation. Existing fixture/manual/TCP/WebSocket/game tests remain included in full Maven regression.

Actual verification on 2026-10-13:

| Command / suite | Result |
|---|---|
| Native isolated image build | SUCCESS, static guest compiled with -Wall -Wextra -Werror |
| mvn clean test (all modules) | BUILD SUCCESS; 300 tests, 0 failures/errors/skips |
| mvn package (all modules, after API gate test addition) | BUILD SUCCESS; 301 tests, 0 failures/errors/skips |
| Real Python worker boundary suite | 5 PASS; 0 skipped |
| Explicit VerifiedWorkerSecurityIT + SavedBotBattleSecurityIT, final source | 8 PASS; 0 failures/errors/skips; BUILD SUCCESS |
| Final affected-module package + two fail-closed gate tests | BUILD SUCCESS; latest bot-runner/bot-match jars and runtime dependencies generated |
| pnpm test | 23 PASS in 7 files |
| pnpm build | SUCCESS (TypeScript + Vite production build) |
| Live exact-image capability gate | 19/19 controls verified, 21/21 probes PASS |
| Docker execution scopes after final verification | 0 remaining project-labeled containers |

There are 309 distinct successful JUnit tests across ordinary regression and the explicit real-sandbox suites, plus 5 Python tests. Native probes run inside real worker startup and are reported separately, not counted as additional JUnit tests. The first worker-crash test used unavailable Windows process command-line metadata; it was corrected to terminate the actual owned supervisor, and all final suites passed. No test was skipped to obtain success.

[Capability evidence](phase8f-capabilities.json) records the tested image/control statuses without authentication or worker ownership tokens. The runtime never loads this report as approval; each startup verifies its own environment. Local evidence logs are under ignored `.tools/phase8f/`; source tests and reports are tracked deliverables. No manual browser test or public hosting security validation is claimed.

Frontend production verification temporarily used `VITE_WS_URL=wss://build-verification.invalid/ws` to satisfy the existing production guard, restoring the environment afterwards. This URL is only build verification; do not deploy that generated placeholder bundle. Production URLs/configuration were not changed. Local Vite DEV uses the existing local API settings.

## Operational limits

- All 19 mandatory controls verified locally; no mandatory control remains unverified in the tested environment. A different or unavailable runtime must pass its own preflight and otherwise remains NOT_EXECUTABLE.
- Linux x86_64 guest only. ARM, native Windows isolation, other Docker engines, hostile co-tenants, kernel/runtime exploit resistance and long-duration load are not certified by this phase.
- Docker/WSL2/kernel/native harness and trusted supervisor are the security TCB. Keep them patched; pinned builds provide reproducibility, not immunity from vulnerabilities.
- Capability report requires live verification, is never a user-controlled eligibility toggle. Source acceptance/parse/hash are not safety proofs.
- One active execution (four service queue slots), one active arena battle, 16 retained matches; 32 bots/16 immutable versions each; 32 local workspace identities. Capacity exhaustion requires a local backend restart. No accounts, resume or persistence.
- Saved sources, browser identities, history and matches are in memory. Restart loses all data; crashed matches are cancelled, never resumed/retried as if an action had not happened.
- Docker startup/cleanup overhead can make a 200-turn match substantially slower than fixtures. Frontend monitoring stops after ten minutes without inventing a result.
- Cleanup needs a reachable local Docker daemon. Unknown/unconfirmed cleanup revokes admission; owner-scoped reconciliation is attempted after supervisor death. A host/daemon outage can require operator recovery. No unrelated containers/images are removed.
- No arbitrary bot execution on Render Free; production ServerMain/WEB_ONLY has no worker or arena route. No Internet exposure, deployment or account changes were made.
