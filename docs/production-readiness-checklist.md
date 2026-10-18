# Production readiness checklist — Phase 11A

**Internet competitive execution: BLOCKED.** PASS means only the stated scope has evidence. It is not a staging/security certification. No public deployment or arbitrary online execution occurred. Phase10.9 local code/report exist; release acceptance remains conditional on the missing staging evidence.

| Status | Check / scope | Evidence and remaining release gate |
|---|---|---|
| PASS | Actual Phase10.9 implementation present | `frontend/src/MatchViewer.tsx`, `CompetitiveHistory.tsx`, `BotProfile.tsx`; `persistence/CompetitiveReadService.java`; `phase10-9-report.md`; CompetitiveReadPostgresIT and frontend Competitive.test.tsx |
| PASS | Current competitive service remains local | `SecureLocalArenaMain`, `LocalArenaHttpServer` bind127.0.0.1; frontend local-submissions DEV guard; new ProductionPreflightTest rejects online environments |
| PASS | Offline configuration validation | ProductionPreflightTest: required declarations, HTTPS/exact origin, cookie/CSRF, TLS overrides, weak secret hygiene, worker policy, one coordinator, redacted immutable diagnostics; online launch always refused |
| PASS | Full Maven regression/build | Phase11A rerun, 325 tests; log `.tools/phase10/phase11a-regression.log`; package result recorded in report |
| PASS | Local authentication/session/owner security | PostgresPersistenceIT: password hashes, opaque sessions, expiry/revoke/restart, ownership and durable source/version; actual PostgreSQL17 |
| PASS | Local Origin/cookie/CSRF defenses | PostgresPersistenceIT.httpCookieCsrfAuthorizationPrivacyAndLogout: missing/forged Origin, cookie/proxy spoof rejection, exact credentialed CORS, logout; not real-browser HTTPS SameSite testing |
| PASS | Queue concurrency and gate refusal (single-app scope) | RankedPostgresIT concurrent join/reserve, owner/bot uniqueness, expired tickets, unavailable gate and request rate bounds |
| PASS | Rating duplicate prevention and rollback | RatingPostgresIT concurrent duplicate/hash/mode ownership/period/restart tests; RankedPostgresIT swapped games/results; V4 immutable unique ledger |
| PASS | Worker and restart integration (local host only) | PostgresWorkerIT: verified Docker worker executes tournament/ranked, persisted frozen versions/replays and idempotency; PostgresPersistenceIT recovery does not rerun unknown execution |
| PASS | Replay privacy and consistent read | CompetitiveReadPostgresIT authorization, source privacy, legacy/running state and MVCC snapshot tests; API view does not confer public spectator access |
| PASS | Frontend tests and production compile | `pnpm test`:63 tests; `pnpm build`: TypeScript/Vite success; production competitive routes remain disabled |
| BLOCKED | Public competitive Java runtime | Current Docker builds server room graph, while authenticated bot-match graph is local-only. New production composition/transport absent |
| BLOCKED | HTTPS cookie, CSRF and trusted proxy implementation | Local cookie intentionally lacks Secure; no production CSRF token adapter/shared rate limit/trusted proxy policy yet |
| BLOCKED | Remote worker trust and Linux isolation | VerifiedWorkerRunner uses local private Python pipe; no remote mTLS adapter, fencing or target-host attestation; Docker Desktop does not prove production boundary |
| BLOCKED | Horizontal application scaling | Database global session advisory lease + TournamentService in-memory projection require one whole app writer; coordinator count config alone insufficient |
| BLOCKED | Durable execution claims/recovery | RankedMatchCoordinator process-local slot; no worker epochs/heartbeats/durable dispatch outbox; existing startup recovery is single-app only |
| BLOCKED | Production PostgreSQL security and operations | Database.open has no pool/TLS enforcement; migrate uses same identity. Need pool, verify-full CA, role split, PITR and restore rehearsal |
| BLOCKED | Storage and retention | V5 ranked game snapshot lacks SQL size constraint; response cap occurs after serialization; audit pruning is not a background production retention job; policy needed for immutable ledger/account erasure |
| BLOCKED | Observability and incident procedures | No competitive readiness contract, fleet metrics/alerts, production redaction review or measured restore RPO/RTO |
| NOT TESTED | Cross-origin HTTPS cookies/CSRF in browsers | No staging domains/TLS/proxy. Need Chromium/Firefox/WebKit credentials/login/logout/expiry/token attacks, same-site and cross-site variants |
| NOT TESTED | Target Linux worker security/load | Need cgroup/kernel/runtime escape/exfil/limits/cleanup/host isolation proof and image rollout tests on actual provider |
| NOT TESTED | Distributed failure and DB unknown commit | Need delayed/stale worker replies, lease partition, rolling deploy, process death at every durable transition, duplicate result storm |
| NOT TESTED | TLS database and backup recovery | Local DB uses loopback test credentials; no managed CA/negative-cert tests or PITR restoration |
| NOT TESTED | Internet/staging UX and capacity | No staging deployment/browser acceptance, proxy load, cold-start SLA or production resource budget measurements |

All PASS test evidence is the Phase11A rerun listed in `phase11a-report.md`; named IT suites run explicitly because normal Surefire `test` does not include `*IT`. Targeted browser/cloud checks stay NOT TESTED even when offline declarations pass.

## Phase11B implementation sequence and acceptance

1. Production HTTPS transport/composition using existing domain services, environment-specific frontend API adapter, trusted proxy, secure cookies, CSRF bootstrap/token and distributed login limits. Keep deployment disabled until review; test real HTTPS domains and session/IDOR matrix.
2. Managed PostgreSQL configuration with verified CA, separate migrator/runtime identities, bounded pool and additive migration for missing payload constraint/retention metadata. Test failed migrations, pool exhaustion, certificate denial and backup restoration. Never rewrite V1–V5.
3. Separate Linux worker with authenticated private protocol, hardened isolation, image provenance, fleet gate, per-execution quotas/deadlines. No DB/session credentials or control socket in guest. Fail gate => no submission execution. Prove actual host controls; no attestation boolean bypass.
4. Single competitive application writer/coordinator pilot first. Implement durable job intent, lease generation, fenced commit/recovery and shared tournament/ranked/manual admission before distributed workers. Test each crash boundary and preserve completed game1 when game2 fails. Multiple API writers require removing in-memory projection assumptions as a separate migration.
5. Instrument sanitized audit/log/metrics/readiness, enforce bounded storage, abuse review and shutdown/drain procedures. Rebuild ledger equality after restore; retain existing policyV1 outputs/3–1–0 and regression tests.
6. Authorized private staging rollout, browser acceptance/security review/load and recovery drills. Require documented RPO/RTO, resource ceilings and explicit release approval. Phase11A does not grant that approval or start11B.

Decisions before11B: hosting/domain ownership and budget; microVM vs reviewed gVisor provider; single-writer initial capacity; RPO/RTO/retention and abuse policy; BFF vs same-site custom domains; security-review owner. Defaults proposed in architecture are design targets, not silently deployed choices.
