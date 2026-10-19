# Register/Login password update

Registration now requires at least 8 characters and at most 72 UTF-8 bytes. The existing JavaScript/Java string-length counting semantics are preserved. Server validation remains mandatory; control characters and malformed UTF-8 input remain rejected. BCrypt cost 12, session, cookies, CSRF and endpoint contracts are unchanged. Login does not apply the registration minimum to existing accounts.

Register adds a frontend-only Confirm Password field. A mismatch prevents the API request and retains inputs. Requests still contain only `username` and `password`. All password fields start hidden and have independent accessible visibility buttons. API rejection retains the entered values; successful registration clears both password fields. No passwords are logged or persisted by the UI.

## Files

- Frontend: `frontend/src/Auth.tsx`, `frontend/src/auth.css`, `frontend/src/Auth.test.tsx`.
- Backend: `bot-match/src/main/java/dev/battlecode/botmatch/persistence/AuthService.java`.
- Backend tests: `bot-match/src/test/java/dev/battlecode/botmatch/PostgresPersistenceIT.java`, `staging-web/src/test/java/dev/battlecode/staging/StagingPostgresIT.java`.
- Policy documentation: `docs/phase10-report.md`, `docs/production-architecture.md`, this document.

## Deployment boundary

Changes are uncommitted on `deploy/safe-staging-vercel`. This branch updates Vercel frontend only. The shared AuthService change must also be transferred, after approval, as a separate scoped change to Render's `feature/phase11b1-safe-staging` branch. Inspect that branch and its independent changes before transferring backend files; do not merge Vercel deployment configuration into it. Render requires its separately approved deployment if Auto Deploy is disabled.

Until the updated backend is deployed, staging will continue rejecting registration passwords shorter than its existing 15-character minimum. Update Render staging before exposing the new frontend policy. No changes to main, the old production project, environment variables, database schema or bot execution are required. No commit, push or deployment is performed as part of this implementation.

## Verification

Automated coverage includes 7/8/longer registration boundaries, confirmation mismatch and matching submissions, unchanged JSON payload, independent visibility controls without submission, input retention after client/server rejection, UTF-8 byte limits, server-side validation bypassing the browser, and login with a pre-existing seven-character BCrypt credential.

Verified locally:

- `pnpm test`: 74 tests passed across 16 files, including routing/session/workspace/arena/tournament regression coverage.
- `node --test bff-tests/proxy.node.mjs bff-tests/routing.node.mjs`: 13 tests passed.
- `pnpm build` with staging runtime environment: TypeScript and Vite build succeeded.
- `mvn -P local-bots,safe-staging clean test`: BUILD SUCCESS; 331 regression tests, no failures/errors/skips.
- `mvn -P local-bots,safe-staging package`: BUILD SUCCESS, with tests enabled.
- Explicit `PostgresPersistenceIT,StagingPostgresIT`: 15 integration tests passed against disposable local PostgreSQL 17, no failures/errors/skips. The test container and generated credentials were removed afterwards.

No manual browser, cloud or deployment verification was performed for this change. Cloud verification must follow separately approved staging deployments; current cloud behavior is not evidence of this uncommitted change. Other explicit PostgreSQL worker/ranking/TLS IT suites were not rerun; their implementation was unchanged.
