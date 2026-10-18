# Phase 11A — Online architecture and production readiness

Date: 2026-10-16. Outcome: architecture/configuration preparation completed; **Internet competitive deployment BLOCKED**. No staging infrastructure or production isolation evidence is available. No public execution, deployment, commit or push was performed. Phase11B has not started.

## Audit outcome

Phase10.9 source and report exist and local automated behavior was reverified. Participant-only viewer, replay controls, profile/rankings/history and auth boundaries are implemented; the original10.9 request explicitly permits participant-only spectator scope. Browser E2E/mobile visual acceptance and target staging remain unverified. Therefore local feature implementation is present, but production completion cannot be inferred from a phase number.

| Area | Actual source inspected | Finding |
|---|---|---|
| Execution | `bot-runner/VerifiedWorkerRunner`, `isolated-worker/worker.py`, guest sources/Dockerfile, security test contracts | Real local capability verification and fresh guests; local supervisor has Docker authority, no authenticated remote worker adapter or production host proof |
| Tournaments | `bot-match/tournament/TournamentService`, PostgresTournamentRepository, TournamentServiceTest/PostgresWorkerIT | Frozen selections and durable replay/checkpoint; write-through in-memory projection and single lifecycle |
| Auth/persistence | `persistence/AuthService`, Database, PostgresSubmissionRepository, V1–V5 | bcrypt/hashed opaque sessions/owner versioning/transactions; HTTP local cookie, no production CSRF/proxy/TLS/pool/role split |
| Ratings | `rating/RatingPolicy`, RatingEngine, RatingService, V4 | Existing Points3–1–0 and ratingV1 unchanged; unique immutable ledger + global advisory lock; production period/restore operations missing |
| Ranked | `ranked/MatchmakingService`, RankedMatchCoordinator, MatchmakingPolicy, V5 | Atomic DB reservation and dedup under single app; process-local execution slot and startup recovery, no distributed fencing |
| Spectator | `persistence/CompetitiveReadService`, MatchViewer/competitive.tsx/CompetitiveHistory/BotProfile, CompetitiveReadPostgresIT | Authorized bounded snapshots and MVCC read consistency; no public spectator or source leak permission added |
| Deployment | Root Dockerfile/.dockerignore, compose.local.yml, server launch config, frontend/vercel.json/.env.example | Render Docker is WEB_ONLY room graph; competitive APIs/worker are separate local graph; PostgreSQL Compose is development, frontend production guard intact |

The source paths above are relative to each module's `src/main/java/dev/battlecode/...` unless scripts/frontend/resources are named. Detailed findings and diagrams: [production architecture](production-architecture.md). Security matrix: [threat model](production-threat-model.md). Release gates and11B plan: [checklist](production-readiness-checklist.md). Official hosting references: [deployment options](deployment-options.md).

## Files changed in this phase

New:

* `bot-match/src/main/java/dev/battlecode/botmatch/production/ProductionPreflight.java` — offline policy validation with non-sensitive immutable diagnostics; unconditional online runtime refusal.
* `bot-match/src/test/java/dev/battlecode/botmatch/production/ProductionPreflightTest.java` —6 tests covering defaults, environment guard, required declarations, unsafe cookie/origin/TLS/secret/worker/coordinator configuration and no online activation.
* `config/local.env.example`, `config/staging.env.example`, `config/production.env.example` — explicit split; staging/production are offline policy declarations with no deployable secret/default.
* `docs/production-architecture.md`, `production-threat-model.md`, `production-readiness-checklist.md`, `deployment-options.md`, this report.

Modified:

* `bot-match/src/main/java/dev/battlecode/botmatch/SecureLocalArenaMain.java` — guard before DB/worker startup.
* `bot-match/src/main/java/dev/battlecode/botmatch/LocalArenaHttpServer.java` — same environment guard before constructing HTTP/business runtime.
* `bot-match/src/test/java/dev/battlecode/botmatch/PostgresPersistenceIT.java` — strengthens existing cookie/CSRF test with authenticated forged Origin/proxy header attacks and exact credentialed CORS assertions.
* `README.md` — links/readiness warning/configuration scope.

Earlier phases already had substantial uncommitted files; they were preserved. No game, rating policy, migration, frontend source, dependency or deployment entrypoint was changed in11A.

## Configuration contract

Existing local DB fields remain `BATTLECODE_DB_URL`, `BATTLECODE_DB_USER`, `BATTLECODE_DB_PASSWORD`. `BATTLECODE_ENV` defaults to local only when absent; local competitive main/HTTP reject blank, unknown, staging/production, RENDER and WEB_ONLY. The existing room server WEB_ONLY remains independent and unchanged.

Additional **offline-only** policy fields:

| Fields | Required declaration |
|---|---|
| BATTLECODE_ENV | staging or production for offline validation |
| BATTLECODE_PUBLIC_API_ORIGIN, BATTLECODE_TRUSTED_ORIGIN, BATTLECODE_WORKER_ORIGIN | One exact HTTPS443 origin each; no credentials/path/query/wildcard/loopback |
| BATTLECODE_COOKIE_NAME, COOKIE_SECURE, COOKIE_HTTP_ONLY, COOKIE_SAME_SITE (all BATTLECODE_ prefixed) | __Host-battlecode_session, true, true, Lax |
| BATTLECODE_CSRF_POLICY | session-token |
| BATTLECODE_DB_URL | PostgreSQL URL with only sslmode=verify-full query; no embedded credentials/SSL override |
| BATTLECODE_DB_USER/PASSWORD | Dedicated role, password hygiene minimum32chars/12distinct, no obvious placeholders; real secret manager generation still required |
| BATTLECODE_WORKER_AUTH/ISOLATION | mtls and microvm or reviewed gvisor |
| BATTLECODE_ACTIVE_COORDINATORS |1; this alone does not permit multiple application writers |

`ProductionPreflight.validate(Map)` does not read files, resolve DNS, connect DB, establish TLS, load mTLS certificates, implement CSRF or verify isolation. It checks declarations only. A clean result never enables execution: `requireOnlineRuntime(Map)` always throws. Blank secrets and `.invalid` hosts in templates are intentional. Runtime enforcement/adapters/certificates remain11B blockers. No `WORKER_VERIFIED=true` or equivalent bypass exists.

## Verification executed

Environment: Java21.0.12.1, Maven3.9.16, project pnpm11.25.0; PostgreSQL17 disposable container, dynamically assigned loopback port; actual pinned Docker QuickJS worker via existing supervisor. Docker Desktop was started hidden when daemon was unavailable. Test credentials were generated only in ignored `.tools/phase10`; no development database was used.

| Command / scope | Result |
|---|---|
| `mvn -P local-bots clean test` | BUILD SUCCESS;325 tests,0failures/errors/skips |
| Explicit20 PostgreSQL ITs including actual verified worker | BUILD SUCCESS;20tests,0failures/errors/skips |
| Focused updated PostgresPersistenceIT cookie/Origin test | BUILD SUCCESS;1test,0failures/errors/skips; run after adding attack assertions |
| `mvn -P local-bots package` | BUILD SUCCESS;325 regression tests rerun,0failures/errors/skips; artifacts packaged |
| `pnpm.cmd test` in frontend |63tests across15files passed |
| `pnpm.cmd build` in frontend | TypeScript and Vite production build succeeded |

The explicit `local-bots` profile includes bot-runner/bot-match in the full reactor; plain Surefire does not select `*IT`. Explicit integration invocation:

```powershell
# Create only the disposable test DB; requires Docker Desktop running.
$dockerPath='C:\Users\Admin\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe'
& .\scripts\start-phase10-test-db.ps1 -Docker $dockerPath
$testConfig=Get-Content .tools/phase10/postgres-test.json -Raw | ConvertFrom-Json
$env:BATTLECODE_TEST_DB_URL=$testConfig.url
$env:BATTLECODE_TEST_DB_USER=$testConfig.user
$env:BATTLECODE_TEST_DB_PASSWORD=$testConfig.password
mvn -P local-bots -pl bot-match -am test '-Dtest=CompetitiveReadPostgresIT,RankedPostgresIT,RatingPostgresIT,PostgresPersistenceIT,PostgresWorkerIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dbattlecode.worker.python=C:\Users\Admin\miniconda3\python.exe' "-Dbattlecode.worker.docker=$dockerPath"
```

Worker prerequisites use the existing Phase8F scripts/image; this command does not provision cloud isolation. Test breakdown: CompetitiveRead4, Ranked5, Rating3, Persistence6, Worker2. The updated focused cookie test is an additional rerun, not a21st distinct IT. Logs retained locally under ignored `.tools/phase10/phase11a-{regression,postgres,origin,package,frontend-test,frontend-build}.log`. After verification, the owned disposable PostgreSQL container was label-checked, stopped and removed; both temporary credential files were deleted. Existing worker image and user databases were preserved.

Frontend build used process-only `VITE_WS_URL=wss://build-verification.invalid/ws` to validate the mandatory production URL setting without a live service; no environment file/deployment was changed. Competitive frontend remains DEV-only. Tests do not claim that placeholder service exists.

No HTTPS browser/staging, cloud microVM/gVisor, managed TLS DB, backup restore, distributed partition/lease or production stress test was run. Local Origin checks and policy tests cannot establish browser cookie delivery or CSRF security for a new production transport. Detailed PASS/BLOCKED/NOT TESTED classification is in the checklist.

## Handoff

Blocking priorities: public competitive transport with HTTPS auth/CSRF/trusted proxy; isolated remote worker and private authenticated protocol; DB TLS/roles/pool/PITR; fenced durable execution/recovery; retention/observability and target-host testing. Initial service must retain one whole application writer, not merely one Ranked coordinator. No horizontal scaling claim is supported.

Phase11B implementation order and acceptance criteria are in the checklist. Hosting/domains/budget, isolation provider, retention/RPO/RTO and review ownership need decisions before that implementation. No reapproval is needed for the completed11A audit/config/tests; production execution/deployment has not been authorized.
