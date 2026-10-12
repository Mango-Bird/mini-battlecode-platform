# Kế hoạch triển khai

Phạm vi cập nhật theo yêu cầu người dùng: Phase 1 contracts, Phase 2 TCP runtime, Phase 3 session/lobby/room, Phase 4 generic GameEngine/Match lifecycle, Phase 5 production rules. Những mục bên dưới là kế hoạch ban đầu được giữ để tham khảo; không phải chỉ thị cho các phase đã hoàn thành. Báo cáo từng phase ghi phạm vi nghiệm thu thực tế.

## Phase 1 — hoàn thành bộ khung

7 Maven module, model immutable, interfaces, codec 1.0, tài liệu, JUnit 5. Không luật chơi, frontend, database hoặc server runnable. Build `mvn test` và `mvn package`.

## Phase 2 — TCP và competition thực tế

1. Thêm module server/composition root và adapter RequestDispatcher; giữ dependency graph không cycle.
2. Cài TcpServer bằng ServerSocket/Socket, virtual threads, ConnectionManager, reader/writer bounded, deadline tổng frame, shutdown idempotent.
3. Cài handshake, bind session vào socket, request correlation/dedup/quota, direction/schema validation và rate limit.
4. Cài Lobby/MatchManager với mutation serialize; phòng có config riêng; LIST_RULES trả rỗng đến khi luật thật được cài, CREATE_ROOM rule lạ bị RULE_NOT_FOUND.
5. Cài GameEngine orchestration; dùng GameRule test double chỉ trong test để kiểm tra lượt, timeout, disconnect và result terminal, không đóng gói luật demo trong production.
6. Cài TCP CLI, reader demultiplex event/response và BotClient chạy strategy ở máy client. Quy định timeout client và cleanup.
7. Integration test loopback port 0: nhiều client đồng thời, handshake sai, spoof session, join cuối cùng tranh slot, hai owner start, duplicate request, split/coalesced frames, oversized/truncated frame, slow sender/reader, disconnect giữa trận, timeout/action race và shutdown không rò thread/socket.

Tiêu chí nghiệm thu: ít nhất 20 client cùng kết nối và thao tác phòng theo test, timeout deterministic qua clock được inject, mọi error theo docs/protocol.md, không network import game/competition, `mvn verify` thành công. Không cần frontend/database.

## Phase 3 — luật đầu tiên

Chọn luật với người dùng, cài module rules-impl hoặc module mới chỉ phụ thuộc rules-api. RuleState immutable, view riêng theo người chơi; đăng ký qua composition root. Unit test luật và integration match end-to-end. Không chạy source không đáng tin cậy trên server.

## Phase 4 — mở rộng có kiểm soát (kế hoạch lịch sử)

Thêm luật thứ hai để chứng minh khả năng thay thế; load test, logging/metrics, hardening giới hạn tài nguyên. Auth/TLS, persistence hoặc frontend chỉ triển khai khi có yêu cầu và đặc tả riêng.

## Phase 5 — production game rules (hoàn thành)

Production registry đăng ký `room-only-no-gameplay`, `rock-paper-scissors` và `number-guessing`. Hai luật gameplay chạy qua cùng GameEngine, MatchManager và TCP protocol 1.0; chi tiết, demo, giới hạn và kết quả test trong [phase5-report.md](phase5-report.md).

## Phase 7 — Web multiplayer và chuẩn bị deployment (hoàn thành)

WebSocket gateway Jetty dùng chung các dịch vụ TCP hiện có; RoomDetails member-only và RoomNotification snapshot đã được người dùng phê duyệt, TCP1.0 giữ nguyên. React/TypeScript/Vite có lobby/room/host/RPS/Number Guessing/result, frontend ngoài Maven. Vercel frontend configuration và Java backend deployment guide đã chuẩn bị; chưa deploy public hoặc xác minh LAN hai thiết bị. Kết quả và phạm vi: [phase7-report.md](phase7-report.md), [websocket-protocol.md](websocket-protocol.md), [web-deployment.md](web-deployment.md). Dừng tại Phase7; không tự triển khai Phase8.
