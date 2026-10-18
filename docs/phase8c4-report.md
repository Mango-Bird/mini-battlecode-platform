# Phase 8C.4 — Local BOT match integration

2026-10-13. Implemented internal coordinator and actual JavaScript bot-vs-bot integration. No frontend or submission endpoint.

## Composition and API compatibility

`bot-match` is an opt-in local composition module, activated with bot-runner by Maven profile `local-bots` (automatic on Windows). It depends on bot-runner, competition and rules-impl; server production has no runner dependency. `LocalBotDemoMain` composes one existing DefaultGameEngine and one DefaultMatchManager/registry/lobby, then injects the same manager into BotMatchCoordinator. No second engine or room system.

The extra local module avoids making Docker/Render require the Windows QuickJS prototype. Dockerfile, ServerMain, TCP/WebSocket dispatchers and deployment configuration are unchanged by8C. Existing Linux `-pl server -am` build graph stays independent. Existing public GameEngine/GameRule/MatchManager interfaces and production game rules are unchanged.

`DefaultMatchManager` implements the new internal BotMatchAccess capability: startBotMatch, captureBotTurn, commitBotTurn, forfeitBotTurn, cancelBotMatch. It retains latest committed immutable GameState references. Trusted OTT observation projection happens under lifecycle lock from that state, never by parsing compact view or reflection.

## Lifecycle/concurrency

Bot rooms have10000ms turn deadline, two server-created seats and frozen submissions. Manual submitAction rejects these seats. One outstanding lease per match contains expectedTurn/generation/random token/typed observation. Commit validates the current lease under manager lock before calling the existing expectedTurn engine overload. Duplicate/stale/terminal leases are no-ops; no Runner I/O under competition/engine locks.

BotMatchCoordinator permits at most4 active internal matches, executes the active side outside locks, checks result executionId/schema/legalMoves and commits through manager. Valid actions use the existing string-map rule adapter. Proven bot failures pass an empty action to the existing OTT adjudication path, producing FORFEIT; new failure codes are not added to wire enums. Infrastructure cancels through manager terminal notifications/room cleanup, never fake disconnect.

BOT watchdog cancels unexplained deadline expiry as BOT_INFRASTRUCTURE_DEADLINE; it never invokes generic engine timeout to guess a loser. Manual tick/disconnect behavior remains unchanged. Disconnecting an internal bot seat cancels as infrastructure; human viewers are not these seats. Cancellation/shutdown invalidates leases, signals outstanding executions and rejects late results. Lost-response/crash/restart has no automatic rerun/resume.

## Match output/replay

Immutable Summary contains final GameResult, final public BotObservation, validated MoveEvent history, each submission id/version/source hash, requested resource profile, runtime mode and pinned runtime fingerprint. Replay calls existing Board.replay(history), compares every final piece against the actual rule observation, and does not re-execute JavaScript to establish the result. History is in-memory; no DB/history endpoint/ratings/audit persistence added.

## Verification

13 coordinator integration tests cover real FirstLegalMoveBot vs CapturePriorityBot match with replay equality, actual invalid bot and CPU-evidenced wall timeout FORFEIT, unavailable/crash/wrong-correlation cancellation, manual-seat rejection, one lease, stale/concurrent commits, terminal protection, disconnect, pending-runner cancel, shutdown and actual10-second BOT watchdog cancellation. Existing manual RPS/Number Guessing, TCP/WebSocket and WEB_ONLY regressions run in the full reactor. Final counts and demo output: [Phase8C report](phase8c-report.md).

No arbitrary sources, production sandbox, public API, frontend, tournament, remote Runner, pause/resume or deployment was introduced. Phase8C.5 security/performance validation remains future work.
