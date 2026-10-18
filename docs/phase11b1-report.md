# Phase 11B.1 — Free cloud staging foundation

Completed locally 2026-10-17. Separate deployable staging artifacts and automated verification are provided. **Live Vercel/Render/Neon deployment and HTTPS browser E2E: NOT TESTED.** No cloud account connection, commit, push, deploy or production data import occurred. No remote/arbitrary JavaScript, Ranked or tournament execution was enabled.

## Actual implementation

New `staging-web` Maven module/profile composes a Java21 HTTP service from existing AuthService, PostgresSubmissionRepository and CompetitiveReadService. It has no local-controller fallback, arena, engine, coordinator, runner/worker initialization, TCP or WebSocket listener. Exact method/path allowlist enforced by Java and independently by a same-origin Vercel Node BFF. Config gate requires `staging`, `SAFE_STAGING_WEB_ONLY`, `disabled`; rejects worker/coordinator settings and production. Phase11A ProductionPreflight remains unchanged.

Authentication uses existing opaque hashed DB sessions/bcrypt with Secure/HttpOnly/Strict __Host cookies, pre-auth/session-bound expiring HMAC CSRF, exact Origin/Host checks, authenticated BFF proxy assertion, bounded per-client/global auth rate limits and generic errors. Browser contacts only its Vercel origin, avoiding default-domain third-party-cookie dependence. BFF forwards session cookies server-side over HTTPS with a private shared secret. It does not store passwords/source/session in browser storage and has no arbitrary upstream URL/proxy route.

Staging source CRUD saves exact UTF-8/SHA-256 immutable versions through existing repository with requestId and expectedVersion. Validation remains SOURCE_ACCEPTED/NOT_PERFORMED/NOT_VERIFIED/NOT_EXECUTABLE. Profiles/history/replays keep existing visibility and empty states. Rankings get a new **pure read** snapshot method; old leaderboard method still closes periods locally as before. No score/rating policy changed or synthetic data seeded.

Database supports bounded Hikari pool through an additive Database DataSource constructor, connection-state reset on return,8s acquire/2s validation/4connections (including held lease), explicit TLS verify-full with JRE certificate verification, direct Neon hostname and restricted role. Reconnect closes a lost session lease and reacquires safely; another app owner returns503. Readiness checks V5 and dangerous role privileges. Separate manual Flyway migration entrypoint and SQL runtime grants; V1–V5 unchanged. No automatic migrations or production credentials.

Runtime writes only auth/source/idempotency tables; it cannot mutate ratings/results/queue/tournaments under reviewed SQL grants. Config requires independent random proxy/CSRF/DB secrets (>=43 URL-safe characters; generated32-byte hex values satisfy). No variable alone proves disabled execution: structural composition, route absence, constructor guards and worker-absence tests enforce it independently.

Full route/auth/security/database contracts and Mermaid topology: [architecture](free-cloud-staging-architecture.md). Operator steps, environment and PowerShell smoke/migration commands: [deployment guide](free-cloud-deployment-guide.md). Evidence and release limits: [security checklist](staging-security-checklist.md).

## Files added/changed in this phase

| Files | Change |
|---|---|
| `staging-web/pom.xml` | New opt-in module, Hikari7.0.2, same Jackson2.22 BOM as bot-match, runtime libs packaging |
| `staging-web/src/main/java/dev/battlecode/staging/{StagingConfig,Csrf,StagingHttpServer,StagingMain,StagingMigrateMain}.java` | Strict gate/pool, signed CSRF, explicit safe routes, HTTP entrypoint/shutdown/metrics and operator migration command |
| `staging-web/src/test/java/dev/battlecode/staging/{StagingPolicyTest,WorkerAbsenceTest,StagingPostgresIT,StagingTlsIT}.java` | Config/CSRF/route/pool tests, separate JVM worker denial, actual DB HTTP/role/reconnect/idempotency and TLS tests |
| `staging-web/sql/runtime-grants.sql` | Separate application grants, no execution/rating/DDL writes |
| `bot-match/.../persistence/Database.java` | Additive DataSource and staging lease-reconnect adapter; local constructor behavior unchanged; close failed connection setup |
| `bot-match/.../rating/RatingService.java` | Add leaderboardSnapshot without closeDue; preserve old leaderboard behavior |
| `bot-runner/.../ExecutionEnvironment.java`; VerifiedWorkerRunner, TrustedQuickJsRunner, BotExecutionService | Guards reject real executor construction outside local; no effect on valid local environment |
| `frontend/api/staging/[...path].mjs` | Explicit bounded same-origin BFF; opt-in server-only environment |
| `frontend/bff-tests/proxy.node.mjs`, `frontend/src/Staging.test.tsx` |4 BFF tests and2 staging frontend tests |
| `frontend/src/runtime.ts`, local-submissions.ts, Auth.tsx, session.tsx, layouts.tsx, BotWorkspace.tsx, Platform.tsx | Staging API/CSRF flow, account/editor enablement, execution-unavailable routes and clear banner; preserve local flow |
| `frontend/vite.config.ts`, `frontend/vercel.staging.json` | Staging build without WSS/local URL, separate CSP/routing; legacy vercel.json unchanged |
| `Dockerfile.staging`, `render.staging.yaml`, `config/safe-staging.env.example` | Separate nonroot Java image, Free single-instance template, no embedded domains/credentials/worker |
| `scripts/prepare-staging-test-tls.ps1` | Label-checked disposable DB TLS and isolated JVM test truststore setup |
| Root pom.xml, .dockerignore, .gitignore, README.md | Opt-in profile, exclude .tools secrets from Docker context, ignore Vercel local metadata, staging instructions |
| Four `docs/*staging*`/phase11b1 deliverables | Architecture, guide, checklist and this report |

Earlier unrelated uncommitted changes were preserved. No historical migration, rules/engine, existing server WEB_ONLY, frontend packageManager/lockfile or current production config changed. Existing Dockerfile and frontend/vercel.json were not replaced.

## Verification actually executed

Java21, Maven3.9.16, pinned project pnpm11.25.0; actual PostgreSQL17 test container on loopback/dynamic port, real existing QuickJS Docker verified worker only in old regression suites. Staging suite itself never runs bots. Docker image is Linux Java21; build packages with `-DskipTests` because local trusted Windows fixture tests cannot run inside its build context; all host regression/integration tests below ran separately without skips.

| Verification | Result |
|---|---|
| `mvn -P local-bots,safe-staging clean test` | BUILD SUCCESS;331tests,0failures/errors/skips (325 previous +6 staging policy/worker tests) |
| Existing explicit PostgreSQL suites | BUILD SUCCESS;20tests,0failures/errors/skips: CompetitiveRead4, Ranked5, Rating3, Persistence6, Worker2 |
| `StagingPostgresIT,StagingTlsIT` | BUILD SUCCESS;8tests,0failures/errors/skips:7 HTTP/DB tests +1 real TLS test |
| `mvn -P local-bots,safe-staging package` | BUILD SUCCESS; full331 regression tests rerun and artifacts packaged |
| `pnpm install --frozen-lockfile` | Success; no lockfile removal/format change |
| `pnpm test` |65tests across16files passed |
| `node --test bff-tests/proxy.node.mjs` |4tests passed |
| Staging frontend production build | TypeScript/Vite success with VITE_RUNTIME_MODE=SAFE_STAGING_WEB_ONLY and no WS/local API URL |
| Legacy frontend production build | Success with process-only build-verification WSS placeholder; no real endpoint/deployment claim |
| `docker build -f Dockerfile.staging ...` | Actual image build succeeded |
| Local staging container smoke | Health200, unavailable DB readiness503, absent worker tools/socket, PID1 Java; final image SIGTERM hook completed in2.912s |

Staging DB tests use a fresh schema and separate limited LOGIN role, never superuser in the HTTP runtime. They verify CSRF/Origin/proxy rejection, cookie flags, logout/expiry, owner source privacy, immutable saves, exact duplicate response, strict JSON/body cap, no rating read-side writes, login/register limits, actual lease-backend termination/reconnect,8s-runtime/3s-test bounded acquisition, rejection of privileged role and second-instance503 while original remains ready. Existing read IT supplies the seeded participant/nonparticipant replay privacy matrix; no fake competitive data was introduced into user accounts.

TLS test creates a disposable self-signed localhost certificate and JVM-local truststore: valid verified TLS passes; trusted certificate with numeric hostname fails; untrusted certificate fails. Machine/JRE trust stores unchanged, no plaintext runtime bypass. Test keys/DB secrets remain only in ignored .tools during execution. Early development runs found a new-test syntax error, missing OpenSSL config and Jackson annotation transitive-version mismatch; each was corrected before successful reruns. No failing run is presented as PASS.

Logs: ignored `.tools/phase10/phase11b1-{regression,postgres-regression,staging-it,package,frontend,bff,frozen,staging-build,legacy-build,docker-build,smoke}.log`; baseline memory/smoke JSON adjacent. Surefire reports in module target directories. Both owned test containers were label-checked then stopped/removed; temporary DB/proxy/CSRF credentials, TLS key/certificate and test truststore/config were deleted. Existing user databases/worker images were preserved; the new local staging image remains for inspection. No real browser, public deployment, cloud database connection or production stress/security campaign was performed.

## Memory / cold start scope

Measured local Docker liveness baseline under `--memory 512m --cpus 0.1`, heap128MiB/metaspace96MiB/direct16MiB/code32MiB,4 HTTP threads/queue16/32connections. Final-image baseline: **48.81MiB** cgroup memory, **13.790s** to health200, **2.912s** graceful stop with `stopped` event. Earlier measurement50.42MiB/14.75s illustrates startup variation. Database was deliberately unreachable (readiness503); no bcrypt-login/CRUD/replay load or Neon wake-up is included. These numbers are **not** a Render Free fit/SLA claim. CPU throttling, managed DB cold start, TLS/connection pressure and response serialization can increase memory/latency substantially.

Render Free cold starts and Neon suspension may exceed frontend60s/BFF55s request budgets; show error and manually retry, never silently retry unknown source mutations with a new requestId. Quotas, backup/retention and cost limits require operator confirmation. Data persists in Neon, not container disk; Free services are not guaranteed uptime or production durability.

## Remaining risks / handoff

Cloud/BFF routing, stable Host headers, Secure cookie forwarding and cross-browser same-origin CSRF need actual Vercel/Render HTTPS acceptance. Render Free memory/CPU and Neon roles/TLS/suspend behavior are NOT TESTED live. Proxy secret protects trust boundary over HTTPS but is not worker attestation; rotate privately and prevent HTTP header/body logging. Rate limits are conservative process-local single-instance controls; no distributed scaling/Sybil promise. One global app lease, bounded source/account/version quotas and manual migration/grant lifecycle remain intentional staging constraints. No MFA/password reset/account-recovery/production backup or full observability fleet was added.

Online bot execution remains BLOCKED regardless of config or UI. Full production architecture Phase11A blockers still apply. Operator deployment/rollback is documented but not performed. Existing production deployments and local competition setup are preserved; stop after11B.1 foundation verification.
