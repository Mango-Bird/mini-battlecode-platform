# Phase 8F.4 — web user bot battles

`/bot-workspace` preserves existing editor/CRUD/immutable history and now uses server-issued local workspace ownership. It shows the server's execution eligibility while retaining the distinction between accepted source, static validation and runtime verification. Unsaved drafts never enter battles. An expired token after backend restart is renewed once; other-owner source denial is not retried with a new identity.

`/bot-arena` lists fixtures and saved user bots with server eligibility, selects an immutable version independently for each side and sends exactly botId/version/sourceSha256. Start requires both selections to be executable; no hidden fixture substitution. Existing live board, result, battle log, captured pieces and immutable backend replay controls are reused. Source, worker credentials, execution IDs and internal paths are not rendered.

Polling remains local REST, avoiding changes to web-1. Fetch timeout is 5s; one Start at a time; polls every 500ms, bounded to 10 minutes for container-per-turn matches. On expiry/error the browser stops polling and may explicitly reload state; no result is invented or bot forfeited by frontend timeout. Unmount aborts fetches and clears timers.

Both pages are disabled by the existing production DEV guard. SecureLocalArenaMain is a separate local composition root, loopback-only and refuses Render / WEB_ONLY. No frontend package/dependency, deployment, production ServerMain, GameEngine/MatchManager API or game rule changed in this phase.

Automated frontend coverage includes the exact chosen old version/hash payload, refusal without verified sandbox, repeated Start protection, server-authoritative replay/results, source save/rename/history and network failures. No manual browser test is claimed.
