# Phase 8B — Board Game Engine & Bot API

Hoàn tất ngày 2026-10-13 tại D:\Socket\BattleCode. Phạm vi là board domain/OTT GameRule/bot-1 DTO/history và tests. Không commit/push/deploy, không Phase8C.

## Kết quả

Đã đăng ký trusted rule `ott-board-9x9-v1` trong hai composition roots hiện có. Dùng nguyên GameRule/GameEngine/MatchManager, TCP1.0, WebSocket web-1, Lobby và lifecycle/shutdown. Không thêm engine hoặc dependency/module mới. RPS và Number Guessing không sửa mã nguồn hay behavior.

Fixed initial board đúng12 quân, mỗi side2 quân mỗi loại với ID/tọa độ theo contract. A đi trước, luân phiên. Legal moves gồm đúng1 ô Chebyshev, không tự bắt/same type/counter type/jump/standstill/out-of-bounds. RPS capture immutable. Thứ tự kết thúc: goal → eliminated type → next-side no legal moves → draw sau200 valid moves. Invalid active-turn BotAction hoặc timeout là FORFEIT, không retry/count/history; disconnect cũng FORFEIT. Request session/requestId/stale/out-of-turn giữ guards/error cũ theo xác nhận của người dùng.

Config rule: maxPlayers2, maxTurns>=200, không options. Generic engine outcome finished ưu tiên trước MAX_TURNS nên nước200 hòa đúng contract. Turn envelope engine bắt đầu0; BotObservation/view.turn bắt đầu1, không thay protocol.

## Các file/class

| Vị trí | File/class mới |
|---|---|
| rules-api/src/main/java/dev/battlecode/rulesapi/bot | BotObservation.java, BotAction.java, BotMove.java, BotPiece.java, BotPosition.java |
| rules-impl/src/main/java/dev/battlecode/rulesimpl/board | Board.java, Piece.java, PieceType.java, Side.java, Position.java, Move.java, BoardState.java, MoveEvent.java, FinishReason.java |
| rules-impl/src/main/java/dev/battlecode/rulesimpl | OttBoardRule.java |
| rules-api/src/test/java/dev/battlecode/rulesapi/bot | BotContractTest.java |
| rules-impl/src/test/java/dev/battlecode/rulesimpl | OttBoardRuleTest.java và board/BoardTest.java |
| server/src/test/java/dev/battlecode/server | OttEngineIntegrationTest.java, OttTransportIntegrationTest.java |
| docs | phase8a-technical-contract.md, phase8b-design.md, phase8b-report.md |

Files sửa: ServerMain.java và RoomTcpServer.java chỉ thêm registration/banner; ProductionGameRulesIntegrationTest.java và RoomManagementIntegrationTest.java cập nhật kỳ vọng registry3→4 (giữ assertion tất cả luật cũ); README.md, docs/architecture.md, docs/phases.md bổ sung phạm vi Phase8B. Không sửa pom/deployment config/frontend/network/protocol/engine/competition hoặc public interfaces hiện có.

## Bot DTO, privacy và replay

BotObservation là snapshot immutable: apiVersion bot-1, gameId, playerId A/B, one-based turn, boardSize9, sorted live board và legalMoves, caller-provided remainingTimeMs. Không UUID/session token/server internals. Lists copy/sort thành immutable; Piece/Position/Move/DTO là immutable. Board/BoardState constructors cho custom fixtures là package-private; không public mutation setter cho tests.

BotAction.decode kiểm tra object đã parse: chỉ pieceId/to; to chỉ row/col; strict String ID và integer coordinate, không float/string/boolean/null/extra-field coercion. BoardState.submit phân xử cả logical map và typed BotAction. Không JSON parser hoặc JavaScript executor mới; runner Phase8C phải parse/validate JSON và cung cấp remainingTimeMs thực tế.

Wire adapter chỉ nằm trong rule: action.pieceId, action.to.row, action.to.col dùng payload string-map cũ. Views gọn chứa board/legalMoves trong string values, không một field mỗi legal move, vừa budget120 fields/60000 bytes. Không gửi DTO nested lên TCP/web-1, không tạo API mới. View không có invented remainingTimeMs. Terminal reason/winners dùng fields cũ; terminal board/history không được gửi vì engine terminal view trống.

Validated MoveEvent có move number/side/pieceId/from/to/captured Piece hoặc null. Rule history immutable, tối đa200 events, được lấy từ rule-owned state bằng OttBoardRule.history; Board.replay từ initial board kiểm tra thứ tự/side/from/capture/movement. Đã test replay200 nước và replay có capture, cùng tampered sequence. Không persistent storage/history endpoint/viewer.

## Verification thực tế

| Command | Kết quả |
|---|---|
| mvn -B -pl rules-impl -am test | Focused BUILD SUCCESS trước full reactor; board/rule/DTO và regressions |
| mvn clean test | BUILD SUCCESS,31.981s, kết thúc07:25:37 +07 ngày2026-10-13 |
| mvn package | BUILD SUCCESS,36.740s, kết thúc07:26:15 +07 ngày2026-10-13; chạy lại toàn bộ tests |

JUnit aggregate cuối từ TEST-*.xml: **244 tests,0 failures,0 errors,0 skipped** =195 regression +49 Phase8B mới.

| Module | Tổng tests | Mới | Fail/Error/Skip |
|---|---:|---:|---|
| rules-api | 7 | 4 | 0/0/0 |
| protocol | 11 | 0 | 0/0/0 |
| engine | 33 | 0 | 0/0/0 |
| competition | 13 | 0 | 0/0/0 |
| network | 2 | 0 | 0/0/0 |
| rules-impl | 52 | 36 | 0/0/0 |
| client | 9 | 0 | 0/0/0 |
| server | 117 | 9 | 0/0/0 |

New tests: BotContractTest4; BoardTest26; OttBoardRuleTest10; OttEngineIntegrationTest7; OttTransportIntegrationTest2. Coverage: exact layout/types/IDs, initial/alternate side,8 directions and illegal movement/capture, goals/priority/elimination, next-turn no-move adjudication,200 draw, forfeit/no count, deterministic immutable/sorted observation, typed/shape/type/extra fields, replay, roster/config/privacy/budget, engine turn mapping/stale/outsider/timeout generation/concurrent ACTION/isolation, shared TCP+WS OTT room and two WS peers in WEB_ONLY. NO_LEGAL_MOVES fixture isolates next-turn predicate because full post-move adjudication first checks type elimination; no claim of a full initial-board match reaching this condition.

Existing tests cover RPS/Number Guessing, CLI/bots, framing/handshake/session/host migration/privacy/ordering/concurrency/shutdown, WEB_ONLY health/Origin/startup/resources. New transport tests use real loopback sockets, dynamic ports, finite timeouts and close/await resources after tests. No tests were skipped. During implementation, a fixture lambda compile error, a mistaken no-move fixture and old registry-count expectation were corrected before final successful runs; no failures remain.

Logs (gitignored): phase8b-focused.log, phase8b-clean-test.log, phase8b-package.log. Maven package contains the new rule/DTOs in ordinary module JARs and copied server dependencies. No live browser test, frontend test/build, Internet multiplayer or deployment was performed for Phase8B; WS integration peers are automated clients, not real browsers.

## Sử dụng và giới hạn

Server startup/WEB_ONLY config giữ nguyên. CLI: `create_room ott-board-9x9-v1 2 200 1000`; playerB join, host start; envelope turn0 A gửi `pieceId=A-S2 to.row=2 to.col=2`, sau đó B gửi legal move với envelope turn1. Dùng deadline/expectedTurn hiện có; không lấy view.turn làm expectedTurn. Không có frontend board viewer/bot OTT client strategy; React RPS/Number Guessing vẫn như cũ.

JS contract chooseMove(observation) chỉ là specification/DTO; chưa sandbox, execution timeout enforcement riêng cho JS, code submission, Promise/network/filesystem/subprocess restrictions runtime, tournament, persistent replay hoặc Phase8C. GameEngine deadline callback hiện tại đã kiểm tra timeout→FORFEIT cho OTT. In-memory state/result retention và trusted-rule callback limits vẫn theo kiến trúc cũ. Không production deployment/config changes.

Tham chiếu: [locked contract và clarification](phase8a-technical-contract.md), [thiết kế](phase8b-design.md), [kiến trúc](architecture.md).
