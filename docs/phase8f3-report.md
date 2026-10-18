# Phase 8F.3 — frozen submissions

`LocalSubmissionStore.freezeVersion(botId, version, expectedSha256)` captures the immutable exact UTF-8 bytes under the existing store lock. Wrong/nonexistent version or wrong hash is rejected before runner admission. Saving/deleting later cannot mutate a match's frozen submission. No latestVersion lookup occurs during a match. Version catalog snapshots are captured atomically with immutable detail lists; public projection contains version/hash metadata only.

`LocalArenaService.startSaved` uses existing lobby, manager, engine, rule and BotMatchCoordinator. The verified shared supervisor is borrowed through a per-match runner view, with a fresh bounded execution-service cache per battle. Existing trusted fixture-only entrypoint remains compatible. In a mixed saved/fixture battle both sides use the same verified isolation and limits.

Secure-local source privacy uses a server-issued 256-bit workspace token in `X-Battlecode-Workspace`. The frontend keeps it in sessionStorage, separate from bot IDs and runner authentication. Submission CRUD/source reads require owner identity; names do not grant permissions. Other browsers can see bot/version/hash metadata and select opponents but cannot read, edit, validate or delete their source. Request idempotency is scoped to owner identity. Tokens/sources do not enter a guest observation, battle replay or logs. This is local browser ownership, not account authentication or Internet hosting.

New/additive local contracts:

| Endpoint | Contract |
|---|---|
| GET /api/local/workspace-session | Issue/renew workspace token; allowed exact local Origin required |
| GET /api/local/execution-capabilities | Public executable flag, control statuses, reason; no paths/auth/source |
| GET /api/local/submissions/{id}/eligibility | Eligibility/capabilities only; workspace identity required |
| GET /api/local/bots | Existing fixtures/initial board plus metadata-only user version catalog and execution capability |
| POST /api/local/battles | Existing fixture strings or saved `{botId,version,sourceSha256}` for either side; requestId required |
| GET /api/local/battles/{id} | Existing authoritative snapshot, moves, frames, result and frozen side identifiers |
| GET /api/local/battles/{id}/moves | Existing immutable moves/replay frames |

Example battle body:
```json
{"requestId":"<UUID>","botA":{"botId":"<UUID>","version":1,"sourceSha256":"<64 hex>"},"botB":"CapturePriorityBot"}
```

Battle POST never accepts source. Saved bot admission is server-controlled and rechecked on start. A failed gate returns HTTP 409, invalid selection/hash HTTP 400, missing bot/version HTTP 404, private-source ownership failures HTTP 403. No fixture substitution occurs. Bounds, loopback binding, Host checks and exact Origin allowlist remain in effect; no wildcard CORS.

Source accepted is size/encoding/metadata acceptance. Static parsing remains NOT_PERFORMED and this MVP does not persist per-source runtime certification (NOT_VERIFIED), even after a battle. Environment EXECUTABLE means the verified sandbox may run a saved version, not that the source is safe or correct. Actual battle actions/results remain available through match history.
