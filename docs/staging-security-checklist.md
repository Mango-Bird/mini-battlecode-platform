# Staging security checklist — Phase11B.1

PASS applies to the local automated scope stated. Cloud staging is **NOT TESTED**, not production-ready. Full Internet bot/Ranked/tournament execution remains **BLOCKED** by design and Phase11A.

| Status | Requirement | Evidence / boundary |
|---|---|---|
| PASS | Dedicated safe runtime, no local-controller rebinding | StagingMain and StagingHttpServer constructor/route registry; no engine/arena/coordinator/worker construction |
| PASS | Separate explicit environment gate | StagingConfig requires staging + SAFE_STAGING_WEB_ONLY + disabled, rejects production/worker/coordinator config; StagingPolicyTest |
| PASS | Phase11A gate preserved | ProductionPreflight unchanged; local HTTP guard remains; existing regression tests |
| PASS | Execution/unknown routes inaccessible | StagingPolicyTest, StagingPostgresIT smoke, BFF proxy.node.mjs deny registry; no fallback dispatcher |
| PASS | Real worker cannot initialize in staging process | WorkerAbsenceTest launches a fresh JVM with staging env and verifies VerifiedWorkerRunner/TrustedQuickJsRunner/BotExecutionService reject initialization |
| PASS | Same-origin BFF, exact proxy boundary | BFF tests fixed upstream/overwritten headers/Host+Origin rejection/no secret response; Java checks shared secret and exact host/origin/proto |
| PASS | Cookie attributes/session revocation/expiry | StagingPostgresIT cookie inspection/logout/expiration; existing AuthService/PostgresPersistenceIT hashed opaque token and password tests; browser delivery not proved |
| PASS | CSRF mutations and pre-auth binding | Csrf test binding/expiry/tampering; staging HTTP rejects missing/invalid tokens before mutation; frontend bootstrap test |
| PASS | CORS allowlist and spoofed proxy denial | Java smoke unknown/forged Origin/no proxy403; BFF Host/Origin spoof and header overwrite tests; no wildcard with credentials |
| PASS | Cross-account source/version protection | Real restricted-role StagingPostgresIT owner GET403/list privacy; existing PostgresPersistenceIT PUT/DELETE/versions tests |
| PASS | Replay participant privacy | Existing CompetitiveReadPostgresIT participant/legacy authorization and no source; staging delegates exactly to same projection, no public spectator |
| PASS | Durable request dedup and append-only versions | StagingPostgresIT repeated request returns same detail; version append and expectedVersion; SQL permissions deny UPDATE bot_versions; V1 trigger unchanged |
| PASS | Body/encoding/JSON limits | Source32KiB from repository; HTTP200000bytes/JSON depth8/strict duplicate detection; staging413/400 and BFF payload cap tests |
| PASS | Rate limiting bounded | StagingPostgresIT login10/min/register3/min; per-client+global buckets, one instance; resets on restart, no distributed/Sybil claim |
| PASS | TLS certificate and hostname verification | StagingTlsIT real TLS PostgreSQL: trusted localhost accepted, numeric hostname and untrusted CA refused; no plaintext fallback in config |
| PASS | Least-privilege readiness | Restricted role exercises auth/source CRUD; privilegedIdentityIsNotReady rejects elevated role; denied rating/source-version writes tested; sql/runtime-grants.sql |
| PASS | Bounded pool / acquisition / reconnect | Hikari max4/minIdle0; poolAcquisitionIsBounded; coldLeaseReconnectAndRateLimit kills actual lease backend and readiness recovers |
| PASS | No rating write on staging reads | leaderboardSnapshot omits closeDue; staging SQL role lacks write rights, rating_periods stays empty; old API behavior preserved |
| PASS | Old local competitive behavior | Full Maven regression and20 real PostgreSQL IT incl verified-worker tournament/ranked; frontend local tests |
| PASS | Logs/metrics bounded and private by construction | StagingHttpServer fixed fields/counters, generic database event, no body/SQL logging; private diagnostics only; no public metrics route |
| PASS | Docker build/smoke local only | Separate Java21 image build, HTTP liveness/DB-unavailable smoke and local memory result in report; no Python/QuickJS binary/control socket |
| BLOCKED | Any online execution capability | No executor/coordinator route/composition; no enable/debug bypass flag; future isolated worker phase/review required |
| NOT TESTED | Actual Vercel/Render/Neon deploy | No accounts connected/service created; guide provides operator steps only |
| NOT TESTED | HTTPS browser cookies/CSRF E2E | Need stable Vercel URL/BFF/Render TLS plus Chromium/Firefox/WebKit; local HTTP tests manually supply cookies |
| NOT TESTED | Real Render Free memory/CPU/cold start | Container baseline is local; no cloud measurement or free-tier capacity assertion |
| NOT TESTED | Neon Free suspend/reconnect/quota behavior | Real local PG backend-loss recovery is not a Neon cold-start trial; verify quotas, TLS and role grants on actual staging DB |
| NOT TESTED | Cloud load/edge limits/abuse/rotation | No public stress tests, distributed rate limit or secret rotation drill; single-instance staging and bounded MVP caps only |

## Operator acceptance before marking cloud staging working

1. Separate project identities, exact URLs, private independent secrets, no VITE_ credentials, direct Neon verify-full connection and restricted runtime role; readiness200.
2. Verify __Host Secure/HttpOnly/Strict cookies are set on Vercel, same-origin BFF forwards them, login/logout/expiry/CSRF works in multiple browsers without third-party cookie dependence. Verify caching is no-store.
3. Create/save bots with exact bytes and immutable versions; second account cannot fetch/edit/delete them. No fake games/ratings appear in fresh DB; permitted old replay access stays participant-only.
4. Crafted execution/Ranked/tournament/admin/worker routes remain rejected; inspect image/processes for worker absence. No debug flag may bypass this.
5. Measure Render memory/CPU/latency/cold start, Neon suspend/resume and capacity limits. Test graceful shutdown and ambiguous save retries with same requestId. Check sanitized logs, rollback and secret rotation.

This checklist does not authorize production release or bot execution. Any live failures stay blockers; do not change cookie/TLS/CSRF settings merely to pass a smoke check.
