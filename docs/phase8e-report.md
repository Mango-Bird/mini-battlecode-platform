# Phase 8E — Bot Code Editor & Submission

Implemented 2026-10-13 in D:\Socket\BattleCode. Local-only source management; no commit/push/deploy.

## Delivered

- React route `/bot-workspace`: create draft, rename/edit/save, duplicate, delete, list bots, show server ID/version/hash/status, JavaScript template and bot-1 documentation.
- Dependency-free textarea editor with UTF-8 byte counter, unsaved-change status, beforeunload warning and confirmation before switching/discarding drafts. Network errors/loading and finite 5-second fetch timeouts; pending operations are aborted on unmount.
- Save always creates a new immutable source version, including rename-only saves. Prior versions are selectable read-only without discarding the latest draft. Duplicate creates a new server-issued botId and version 1 from the editor contents.
- Local submission HTTP API in the existing Phase 8D server. No additional listener, runner, engine, database, dependency or trusted fixture registration.
- Arena lists trusted fixtures and user submissions separately. User choices carry submission IDs, show NOT EXECUTABLE, disable Start and retain the selected bot; no silent fixture substitution. The backend still accepts only its existing two fixture IDs.

## API contract

Base: `http://127.0.0.1:8090/api/local`. Exact Phase 8D Origin/Host guards apply to all endpoints. GET requires an allowed Origin as well.

| Method/path | Request | Response |
|---|---|---|
| GET /submissions | none | `{submissions: Metadata[]}`, without source |
| POST /submissions | `{requestId, name, source}` | 201 Detail |
| GET /submissions/{botId} | none | 200 Detail, including immutable source versions |
| PUT /submissions/{botId} | `{requestId, expectedVersion, name, source}` | 200 Detail with appended version |
| DELETE /submissions/{botId} | JSON `{requestId, expectedVersion}` | `{deleted: botId}` |
| POST /submissions/{botId}/validate | `{requestId, expectedVersion}` | Validation for saved latest version |

Every mutation requires a canonical lowercase UUID requestId. IDs are server-generated. JSON fields are exact; unknown/missing/duplicate fields and trailing tokens are rejected. All bodies require Content-Type application/json. No query parameters, execute endpoint, eligibility/debug flag or source-to-runner route.

Detail: `{metadata: Metadata, versions: Version[]}`.

Metadata: `botId, name, createdAt, updatedAt, latestVersion, sourceSha256, validationStatus, executionEligibility`. Timestamps are ISO-8601 UTC strings.

Version: `version, source, sourceSha256, createdAt, validationStatus, executionEligibility`. Source is only returned by explicit workspace detail/create/save operations. List/catalog responses have metadata only.

Validation: `botId, version, sourceSha256, validationStatus, staticValidation, runtimeVerification, executionEligibility, reason`.

Status codes: 400 invalid JSON/UTF-8/source/metadata/schema; 403 Host/Origin denied; 404 missing resource/endpoint; 409 stale expectedVersion, conflicting requestId, capacity or arena busy; 413 request envelope exceeds limit; 503 unavailable infrastructure. Error bodies are fixed diagnostics and never include source, exception messages, paths or credentials.

POST example:

```json
{"requestId":"2bfe31c5-a761-4938-927f-e6b36f4bb3d7","name":"My Bot","source":"function chooseMove(o) { return o.legalMoves[0]; }"}
```

## Versioning, concurrency and bounds

`LocalSubmissionStore` reuses Phase 8C BotSubmission to freeze exact UTF-8 bytes and calculate SHA-256. A strict encoder rejects unpaired Unicode surrogates; no newline/Unicode normalization, trimming or source rewriting. Immutable records/lists retain old versions. Name is 1–80 characters, no surrounding whitespace/control characters, valid Unicode.

Limits: source 1–32768 UTF-8 bytes, at most 32 live bots, 16 versions per bot, 1024 retained successful mutation request IDs, JSON envelope 210000 bytes, depth 8. The larger envelope supports JSON escaping of 32KiB source while keeping the source byte limit independent. Existing battle POST retains its 2048-byte limit.

All store reads/mutations are synchronized. Checking expectedVersion, appending the version and caching the idempotent response happen atomically. A retry of the same request/body returns its original immutable response without another version; conflicting reuse returns 409. Two saves against the same version have exactly one winner. No HTTP I/O occurs under the store lock.

No cache eviction: capacity fails closed until restart. Delete removes the bot from active list/detail lookup; previous successful idempotent responses remain bounded in memory until restart and do not resurrect the bot. The local store is shared by allowed local clients; there is no authentication/multi-user ownership layer in this MVP. All bots, versions and request records are lost when backend restarts.

Future match integration must freeze an explicitly selected botId/version/sourceSha256. Phase 8E does not permit such matches and never promotes stored source to a trusted fixture.

## Validation and security boundary

| Stage | Phase 8E behavior |
|---|---|
| Source accepted | SOURCE_ACCEPTED: valid metadata/UTF-8/size/hash only |
| Static validation | NOT_PERFORMED: no JavaScript parser/linter introduced |
| Runtime verification | NOT_VERIFIED: arbitrary source is never executed |
| Execution eligibility | NOT_EXECUTABLE for every user source, including code identical to a fixture |

SHA-256 is identity/integrity, not evidence of safety. Syntax errors may be stored because this phase does not parse JavaScript. The validate endpoint checks the selected saved version and reports these limitations without running source or modifying old versions.

Phase 8C.5 proven sandbox isolation remains mandatory before user bots may compete. QuickJS prototype remains TRUSTED_FIXTURES_ONLY. No eval, Function constructor, Node vm, browser execution, public deployment endpoint, wildcard CORS or bypass flag was added.

HTTP stays bound to 127.0.0.1 under LocalArenaMain --local-development and local-bots profile; RENDER/WEB_ONLY are refused. Exact allowed origins: http://127.0.0.1:5173 and http://localhost:5173. CORS methods now include PUT/DELETE. Production server/deployment remains independent; both workspace and arena are disabled by frontend production builds.

## Changed files

- Added `bot-match/.../LocalSubmissionStore.java`, `LocalSubmissionStoreTest.java`, `LocalSubmissionHttpTest.java`.
- Modified existing `LocalArenaHttpServer.java`: guarded submission routes, strict encoding/schema/envelope validation, PUT/DELETE preflight, metadata-only catalog extension.
- Added `frontend/src/BotWorkspace.tsx`, `BotWorkspace.test.tsx`, `local-submissions.ts`, `bot-workspace.css`.
- Modified `frontend/src/main.tsx` routing and `BotArena.tsx` catalog/eligibility UI.
- README and this report.
- No changes to GameEngine, MatchManager, OTT/RPS/Number Guessing rules, bot runner/service/fixture registry, TCP 1.0, web-1, Maven dependencies, frontend package/lockfile or deployment configuration.

## PowerShell launch

Stop the previous arena backend, then rebuild and restart it:

```powershell
Set-Location D:\Socket\BattleCode
mvn -Plocal-bots -pl bot-match -am package
.\scripts\run-local-arena.ps1
```

Separate frontend terminal:

```powershell
Set-Location D:\Socket\BattleCode\frontend
$env:VITE_BOT_API_URL='http://127.0.0.1:8090'
pnpm exec vite --host 127.0.0.1 --port 5173 --strictPort
```

If this machine's terminal cannot find pnpm/node, use the existing Codex runtime (temporary PATH for this terminal):

```powershell
$runtimeRoot='C:\Users\Admin\.cache\codex-runtimes\codex-primary-runtime\dependencies'
$env:Path="$runtimeRoot\node\bin;$runtimeRoot\bin\fallback;$env:Path"
pnpm.cmd exec vite --host 127.0.0.1 --port 5173 --strictPort
```

Open `http://127.0.0.1:5173/bot-workspace`. Save a bot, view its version/hash, validate the saved version, then follow Bot Arena to see it marked NOT EXECUTABLE. Choose FirstLegalMoveBot and CapturePriorityBot to run a real trusted-fixture battle.

## Verification

- Focused `LocalSubmissionStoreTest,LocalSubmissionHttpTest`: 7 tests, no failures/errors/skips, BUILD SUCCESS.
- `mvn -Plocal-bots -pl bot-match -am package`: BUILD SUCCESS, 47.754s; 160 tests, no failures/errors, covering the affected dependency reactor and Phase 8C/8D regressions plus new submission tests.
- Final focused package rerun after tightening Unicode metadata validation: BUILD SUCCESS, 7 tests, 4.881s.
- `pnpm test`: 22 tests passed in 7 files (4 new workspace/arena integration tests).
- Frontend TypeScript/Vite production build succeeded with temporary `VITE_WS_URL=wss://build-verification.invalid/ws` to satisfy the existing production guard. That placeholder bundle is for build verification only, not deployment; local environment files are unchanged.

Tests cover exact UTF-8 hash, immutable versions, idempotency/deletion, invalid source/name/Unicode, bot/version caps, concurrent saves, truthful validation of deliberately invalid JavaScript, HTTP CRUD, strict body encoding/schema/extra/duplicate fields, oversize bodies, stale-version conflict, safe diagnostics, metadata-only catalog, backend rejection of submission-ID battles, editor save/version/rename/network errors and frontend eligibility enforcement.

Logs: .tools/phase8e-focused.log, phase8e-package.log, phase8e-final-package.log, phase8e-frontend-test.log, phase8e-frontend-build.log. No manual browser tests, security stress tests, full-reactor clean test or deployment were performed.
