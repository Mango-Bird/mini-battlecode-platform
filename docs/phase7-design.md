# Phase 7 design — hợp đồng đã phê duyệt và triển khai

Ngày khảo sát: 12/10/2026 (Asia/Bangkok). Workspace D:\Socket\BattleCode.

## Baseline

Source và reports hiện có Phase 5 production rock-paper-scissors/number-guessing và Phase 6 bot/benchmark. Báo cáo Phase 6 ghi 175 tests PASS; LAN hai thiết bị vật lý vẫn NOT VERIFIED. Ở bước khảo sát chưa chạy lại regression; full-reactor final verification nằm trong phase7-report.md. Không frontend/WebSocket dependency hiện có.

TCP protocol 1.0 không có host identity, member nickname/list hoặc ROOM_INFO. LIST_ROOMS/ROOM_EVENT chỉ cho player count. Room.ownerId và membership có trong domain; InMemoryLobby.session có nickname nhưng chưa có API member-authorized atomic room projection. Không suy ra host từ thứ tự player hoặc người vừa create; host migration do server quyết định.

Rule views đủ cho gameplay: RPS round/roundsToWin, score.<UUID>, submitted.<UUID>, yourChoice, lastRound/lastChoice.<UUID>/lastRoundWinner. Number Guessing minNumber/maxNumber/currentPlayer/yourTurn/turnsTaken/maxTurns/historyCount/history.<index>.player/guess/hint/turn. Không secret trong running view. Terminal snapshot không có view; dùng winners/reason có sẵn. Number secret chỉ hiển thị khi reason thực sự chứa CORRECT_GUESS;secret=..., forfeit không reveal. RPS terminal reveal là text reason có sẵn, không tự tạo terminal view/score hay tính winner trên frontend.

## API đã được người dùng phê duyệt

1. Bổ sung immutable competition RoomDetails(roomId,ruleId,status,hostId,players,capacity,revision); players chỉ có playerId/nickname, không PlayerSession metadata, GameState hoặc config secrets. Revision tăng ở server trên mutation phòng.
2. Bổ sung default Lobby.roomDetails(sessionId) -> Optional<RoomDetails>; implementation lấy snapshot dưới lifecycle lock và chỉ trả phòng mà session hiện là member. Unknown/closed session bị từ chối, session LOBBY trả empty. Không public arbitrary roomId lookup cho browser.
3. Bổ sung optional RoomDetails vào RoomNotification để enqueue snapshot đúng thời điểm mutation; giữ constructor 5 tham số cũ bằng overload, giữ accessors cũ. Snapshot CLOSED/recipient đã leave được chuyển thành room=null; không gửi roster mới cho người đã rời phòng. Không đổi TCP ROOM_EVENT.
4. Bổ sung WebSocket configuration/constructor cho RoomTcpServer và websocketPort getter, giữ constructors hiện có mặc định TCP-only. Composition root có một DefaultConnectionManager, routes, dispatcher, InMemoryLobby, MatchManager, engine và ordered publisher dùng chung cho TCP/WebSocket. Không bổ sung GameEngine API.
5. Browser JSON và ROOM_STATE là contract web mới, không thêm Command/ErrorCode enum hoặc field trên TCP. Schema ở docs/websocket-protocol.md đã được phê duyệt cùng API trên.

Người dùng đã phê duyệt có điều kiện bốn nhóm API/architecture: RoomDetails, RoomNotification snapshot, shared TCP/WebSocket composition và ROOM_STATE. Implementation tuân theo các điều kiện này; GameEngine và hai production rules giữ nguyên.

## Backend architecture

React browser -> WebSocket connection adapter -> NetworkRequestDispatcher hiện có -> Lobby/MatchManager -> GameEngine -> GameRule.
TCP CLI/bot -> DefaultTcpServer -> cùng dispatcher/services/registry/manager/publisher. Không tạo InMemoryLobby/engine thứ hai. Có thể refactor composition nội bộ nhưng không đổi public constructors/methods cũ.

Jetty embedded 12.1 là dependency đề xuất cho HTTP upgrade và WebSocket adapter, không Spring Boot/Servlet application mới. Jetty hỗ trợ server upgrade handler, message limits và asynchronous send callback; tài liệu chính thức:
- https://jetty.org/docs/jetty/12.1/programming-guide/server/websocket.html
- https://javadoc.jetty.org/jetty-12.1/org/eclipse/jetty/websocket/api/Session.html

Thread model: Jetty bounded platform thread pool cho HTTP/WS I/O; application work dispatch tuần tự theo connection qua virtual tasks và demand/backpressure, không giữ Jetty selector thread trong rule callback. Mỗi connection có bounded outbound queue, một asynchronous send đang chạy; callback lỗi/write deadline đóng connection. Shared manager áp maxConnections cho TCP+WS, giữ rate/quota/request-ID validation ở dispatcher. Gateway có handshake/idle/write watchdog, message JSON 64KiB và protocol codec budget kiểm tra trước dispatch. Shutdown stop admission, cancel matches theo policy, close cả hai transports/scheduler/event worker, cleanup idempotent.

JSON dùng Jackson 2.22.0 với strict duplicate detection, structural/type/size limits. Jetty 12.1.14, SLF4J JDK14 2.0.17 được pin trong server/pom.xml. Không Java serialization/raw RuleState. Jetty giới hạn 32 platform threads và HTTP sockets qua ConnectionLimit; internal continuation queue theo thiết kế Jetty không bị giới hạn cứng. Application admission dùng chung maxConnections, mỗi connection chỉ một demand/handler và một writer; outbound queue có giới hạn cứng.

Security: handshake path /ws, Origin allowlist exact scheme/host/port, từ chối Origin thiếu/không hợp lệ, không wildcard production. WSS production qua TLS reverse proxy tới Java backend private bind; không tin browser player/session/host/winner. Không resume; reconnect HELLO tạo session mới. Không nhúng secrets vào frontend. Health GET /health chỉ trả readiness tối thiểu, không API room HTTP/CORS.

## Frontend và deployment

frontend/ tách Maven, React+TypeScript+Vite, pnpm 11.25.0 và pnpm-lock.yaml; reducer/state tối thiểu và native browser WebSocket. Welcome/Lobby/Room/RPS/Number Guessing/Result, dark responsive theme, accessible controls, errors/loading/empty states. Backend authoritative turn/deadline/views/results; không optimistic turn hoặc winner calculation. Ignore stale room/match events, request correlation/timeouts; timeout đóng connection vì không rõ kết quả, không retry/resume.

VITE_WS_URL cấu hình endpoint; local ws://127.0.0.1:8081/ws; production URL WSS do operator cung cấp. Không có public backend domain hoặc quyền Vercel deployment được cung cấp. Chuẩn bị frontend build/deploy configuration và Java environment/reverse-proxy hướng dẫn; không tuyên bố deployed hoặc hai-device LAN verified.

Vercel: Root Directory frontend, Vite preset, pnpm build, output dist, VITE_WS_URL qua environment rồi redeploy. Theo docs chính thức https://vercel.com/docs/frameworks/frontend/vite và https://vercel.com/docs/environment-variables. Backend Java process lâu dài ngoài Vercel frontend hosting; hướng dẫn trong docs/web-deployment.md.

## Verification

Giữ 175 regressions. Backend mới kiểm tra handshake/origin/JSON/session/commands/events, shared TCP+WS room, privacy RPS/Number Guessing, concurrent ACTION, member-only atomic room snapshots, host migration, bounded queue/slow writer, limits/timeouts/disconnect/shutdown/cleanup. Frontend kiểm tra socket correlation/timeouts, reducer/stale events và welcome validation. Hai browser session đã hoàn thành cả hai production games trên localhost. Kết quả lệnh và giới hạn xác minh xem docs/phase7-report.md.
