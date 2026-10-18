# Phase 8B — Board rule and Bot API integration

2026-10-13. Survey completed for README, architecture/phases/Phase7/7D/web-1, GameRule, DefaultGameEngine, DefaultMatchManager, production rules, registry composition, dispatcher and relevant regression tests. User approved the BotAction/request distinction before implementation. No existing public-contract modification was needed.

## Existing integration

GameRule implementations are stateless; immutable RuleState owns match-specific data. Sequential rules use currentPlayer, default activePlayers and isTurnComplete. Engine serializes each match, validates expectedTurn/deadline/membership/eligible player before calling the rule, and commits an immutable candidate atomically. MatchManager uses the shared Lobby lifecycle lock and publishes domain events outside socket I/O. Registry registration belongs to ServerMain/RoomTcpServer, not dispatcher/gateway.

Limits: engine turn starts0; maxTurns cancels unfinished matches at the configured limit, but finished RuleOutcome takes precedence. Views have at most120 fields and60000 UTF-8 bytes; terminal reason2048 bytes. TCP/web-1 payload values are strings, and malformed web envelopes/types close the connection before a GameRule sees them. Existing rules and tests must remain behavior-compatible.

## Contract ambiguity resolved by user

Phase8A sections2.5/2.7 require invalid action → immediate FORFEIT. Existing engine rejects out-of-turn/stale submissions before invoking the rule; false isActionAllowed produces ACTION_REJECTED without a terminal state. Dispatcher/protocol validation likewise runs before GameRule. Therefore a literal interpretation that every invalid network request must forfeit cannot be implemented solely inside a new rule while preserving those guards.

Evidence: DefaultGameEngine lines95–103; NetworkRequestDispatcher lines105 and187–189; docs/architecture.md Runtime Phase4 states rejected action does not consume eligibility.

### Approved interpretation

FORFEIT applies to an invalid BotAction returned for the active bot's requested turn: invalid shape/type/extra fields, nonlegal piece/destination, or timeout. Authentication, membership, duplicate requestId, stale expectedTurn and unsolicited out-of-turn network requests retain existing protocol errors; they are not a chooseMove return value. This interpretation requires no GameRule/GameEngine/MatchManager API change and no protocol change.

The new rule's isActionAllowed can admit active-player submissions for adjudication (including invalid movement); applyAction validates the move and returns either a move state or terminal FORFEIT state. It must not throw on an ordinary invalid move, since the engine converts unexpected rule exceptions to RULE_ERROR cancellation. Other rules are unchanged. Future bot-1 shape decoding lives in a domain adapter, not TCP/WS transport; no JS execution is introduced.

If FORFEIT must also cover unsolicited wrong-turn/stale/malformed network requests, a generic rejection hook and transport-to-match adjudication policy would need separate approval. Do not bypass eligibility or mark both players active for a sequential game merely to force callbacks.

## Implemented design

- rules-impl board package: immutable Board/Piece/PieceType/Side/Position/Move/BoardState, deterministic generation and validated-move history. Package-private fixture construction for custom test states; no test-only public mutation API.
- rules-api bot package: minimal immutable BotObservation/BotAction/BotMove/BotPiece/BotPosition contracts; observation uses A/B, sorted live pieces/moves, and caller-supplied remainingTimeMs. No session IDs or invented runner budget.
- OTT GameRule in rules-impl, ruleId ott-board-9x9-v1. Private roster-to-side mapping; first roster player A, second B. Engine transport turn0 maps to bot turn1 independently.
- Rule configuration requires exactly2 players, maxTurns at least200 and no unknown options, so generic engine cancellation cannot precede the locked200-valid-move draw. After move200 the rule outcome is terminal before engine checks MAX_TURNS.
- Existing ACTION payload can carry action.pieceId/action.to.row/action.to.col as strings; this is an adapter projection, not a change to the nested bot-1 logical DTO or wire envelope. No new endpoint/command.
- Projection must fit existing budgets: use compact field values for board/legalMoves rather than one field per move. Full replay history stays immutable in rule-owned in-memory state, not unbounded wire payloads.
- Evaluate order remains GOAL_REACHED → PIECE_TYPE_ELIMINATED → next-side NO_LEGAL_MOVES → MOVE_LIMIT_REACHED. Invalid move/timeout does not increment validMoveCount or append validated-move history.
- Add production registration only at composition roots. Update registry-list expectations to include the new ID while keeping all old rules/tests. No deployment changes, frontend board viewer, runner, sandbox, persistence or Phase8C.

## Representations and adjudication

Board/BoardState have package-private constructors and public immutable transitions, not mutation setters. The private OttBoardRule.State retains the two UUID identities and a BoardState; public BotObservation contains only A/B. minimumPlayers/activePlayers/isTurnComplete use existing sequential defaults. First roster entry becomes A (the current MatchManager places the host first); identity does not determine piece ID/side names.

BotAction.decode(Object) accepts an already parsed map with exact pieceId/to keys and row/col integer values (Integer or in-range Long). It does not parse JSON text, execute JavaScript or accept float/string coercion. Phase8C will be responsible for JSON/duplicate-key parsing and execution restrictions. BoardState.submit accepts the logical map or immutable BotAction, validates its shape and move, and returns FORFEIT on invalid active-turn action.

Generic TCP/web ACTION carries pieceId/to.row/to.col under action.*; integer strings must be canonical decimal integers. This projection is not the nested bot-1 schema and not a new protocol. view fields: apiVersion, gameId, playerId (A/B), turn (bot one-based), boardSize, board, legalMoves, validMoveCount, currentSide, yourTurn. board value is sorted semicolon-separated id,owner,type,row,col entries; legalMoves is sorted pieceId,row,col entries. These are compact public projections, not JSON BotObservation. No remainingTimeMs or UUID mapping is fabricated in the view. The typed observation method requires its caller to supply remainingTimeMs.

Terminal reason is the locked FinishReason name, winners map back to the original UUID solely in RuleOutcome. The existing Engine clears terminal views; no board/observation/history is attached to terminal wire events. Full immutable history is obtainable from rule-owned RuleState via OttBoardRule.history; Board.replay reconstructs a board from the fixed initial state and checks sequence/side/from/capture and movement legality. No history endpoint/persistent storage/viewer is added.

Each valid move appends MoveEvent(number,side,pieceId,from,to,captured Piece or null). Invalid action/timeout/disconnect does not append a validated move or increment validMoveCount. NO_LEGAL_MOVES is checked at next-turn adjudication after elimination; its isolated fixture test deliberately tests that stage rather than claiming a complete match reaches it from initial board.

## Verification

Focused rule/DTO/replay tests first; integration with existing engine and TCP/WS, timeout/forfeit/config/budgets; then mvn clean test and mvn package without skipping regressions. Final module totals and results are recorded in phase8b-report.md. No browser multiplayer verification is claimed for Phase8B.
