# Mini Battlecode TCP protocol 1.0

Đặc tả normative cho Phase 2. Phase 1 chỉ triển khai envelope, codec, enums và model. Transport là TCP thuần Java, port mặc định 7777, bind 127.0.0.1. Chỉ triển khai lab/trusted network; phiên bản này không có TLS hoặc account authentication.

## Wire format

Mỗi frame: `int32 length` big-endian, tiếp theo đúng `length` byte body. Length không tính 4 byte header, bắt buộc 1..65,536. Giá trị âm, 0 hoặc vượt giới hạn: đóng kết nối, không cấp phát theo length. EOF trước byte đầu là clean disconnect; EOF giữa header/body là truncated connection, đóng và cleanup. Nhiều frame có thể ghép trong một TCP read; một frame có thể phân mảnh tùy ý.

Body tuần tự:

| Field | Encoding |
|---|---|
| version | string, đúng `1.0` |
| requestId | string, 1..64 ký tự ASCII `[A-Za-z0-9_-]` trên request/response; rỗng trên event |
| sessionId | string, UUID canonical server cấp; rỗng trên HELLO và lỗi trước handshake |
| command | string, tên enum case-sensitive |
| errorCode | string, `NONE` trừ command `ERROR` |
| payload count | uint16 big-endian, tối đa 128 |
| payload | count cặp string key, string value |

String = uint16 số byte UTF-8 + byte UTF-8 chuẩn (không Java modified UTF-8). Tối đa 65,535 byte/string nhưng tổng body phải nằm trong giới hạn frame. UTF-8 lỗi, key rỗng/trùng, error enum sai, errorCode không khớp command hoặc byte dư là MALFORMED_MESSAGE. Encoder sắp xếp key cho kết quả ổn định, decoder chấp nhận thứ tự tùy ý. Envelope giới hạn version 16 ký tự, requestId/sessionId 64; kiểm tra ý nghĩa version/ID thuộc application adapter. Command không biết trả UNKNOWN_COMMAND nếu envelope có thể đọc an toàn; body malformed đóng kết nối, không cố resync. Không dùng Java serialization.

Ví dụ logic (đây không phải JSON trên wire):

```text
1.0 | r1 | "" | HELLO | NONE | {name: "Bot A"}
1.0 | r1 | 550e8400-e29b-41d4-a716-446655440000 | ACK | NONE | {for: "HELLO"}
```

Client chỉ gửi HELLO/PING/LIST_RULES/LIST_ROOMS/CREATE_ROOM/JOIN_ROOM/LEAVE_ROOM/START_MATCH/ACTION/QUIT. Server chỉ gửi ACK/ERROR/ROOM_EVENT/MATCH_EVENT. Response giữ requestId của request; events requestId rỗng. Server đưa sessionId đã bind vào response/event. ERROR có payload `message` là mô tả ngắn cho người dùng, không stack trace. ACK luôn có `for` tên command gốc.

## Commands và payload

Tất cả key không được khai báo bị INVALID_PAYLOAD; key bắt buộc thiếu, số không đúng decimal, UUID sai đều bị INVALID_PAYLOAD. Không dùng ID trong payload để giả danh người chơi: identity luôn lấy từ session đã bind. Tất cả request sau HELLO phải có sessionId khớp connection.

| Command | Payload request | Điều kiện / response ACK |
|---|---|---|
| HELLO | `name` 1..32 ký tự, không blank | Chỉ CONNECTED; version 1.0; cấp session, ACK `for` |
| PING | rỗng | ACTIVE; ACK `for` |
| LIST_RULES | rỗng | ACTIVE; ACK `for,count,rule.0.id,...` |
| LIST_ROOMS | rỗng | ACTIVE; ACK `for,count,room.0.id,room.0.ruleId,room.0.status,room.0.players,room.0.maxPlayers,...` |
| CREATE_ROOM | `ruleId,maxPlayers,maxTurns,turnTimeoutMillis`; optional `option.<key>` | Session LOBBY; rule tồn tại và validateConfig; ACK `for,roomId` |
| JOIN_ROOM | `roomId` | LOBBY, room WAITING và còn chỗ; ACK `for,roomId` |
| LEAVE_ROOM | rỗng | IN_ROOM; ACK `for`; đang trận thì INVALID_STATE, muốn bỏ trận phải QUIT/disconnect |
| START_MATCH | `roomId` | Owner IN_ROOM, room WAITING, đủ số người luật yêu cầu; ACK `for,matchId` |
| ACTION | `matchId,turn`; optional `action.<key>` | IN_MATCH; turn đúng, trước deadline, rule cho phép; ACK `for,matchId,turn` của lượt được chấp nhận |
| QUIT | rỗng | ACTIVE; ACK rồi đóng và cleanup |

Config: maxPlayers 2..64, maxTurns >=1 trong Java int, turnTimeoutMillis 100..300000. Luật có thể siết chặt thêm. String option/action không bao giờ được xem là code thực thi. Match không đổi ruleId hoặc config. ACK ACTION chỉ xác nhận đã xử lý; trạng thái tiếp theo qua event.

Lists phân trang: request LIST_RULES/LIST_ROOMS cho phép optional `offset` integer >=0, `limit` 1..10 mặc định 10. ACK có `nextOffset` hoặc `-1`; schema trên thêm field này. Adapter giảm số item nếu cần để không vượt 128 field/65,536 byte; nếu một item không vừa, INTERNAL_ERROR. Không đưa cấu hình tùy ý vào room list.

ROOM_EVENT: `type` = CREATED/JOINED/LEFT/CLOSED, `roomId,status,players`; gửi đến thành viên bị ảnh hưởng, `players` là count. MATCH_EVENT: `type` = STARTED/STATE/FINISHED/CANCELLED, `matchId,turn`; STARTED/STATE thêm `deadlineEpochMillis` và `view.<key>` từ rule.viewFor; FINISHED thêm `winners` (UUID cách nhau dấu phẩy, rỗng nếu hòa), `reason`; CANCELLED thêm `reason`. Chỉ gửi view riêng cho session tương ứng. Giới hạn event giống request; vi phạm giới hạn từ rule là lỗi nội bộ và cancel trận.

## Errors

| Code | Ý nghĩa / xử lý |
|---|---|
| UNSUPPORTED_VERSION | HELLO version sai; ERROR rồi đóng |
| MALFORMED_MESSAGE / MESSAGE_TOO_LARGE | Wire sai; đóng, ERROR chỉ khi an toàn và có ID hợp lệ |
| HANDSHAKE_REQUIRED | Command trước HELLO; ERROR rồi đóng |
| INVALID_SESSION | Session không khớp socket; ERROR rồi đóng |
| UNKNOWN_COMMAND | Tên command lạ; ERROR nếu có thể correlate an toàn, nếu không đóng |
| INVALID_STATE | Command không hợp trạng thái; kết nối tiếp tục |
| INVALID_PAYLOAD | Payload sai, requestId sai hoặc trùng; kết nối tiếp tục nếu frame hợp lệ |
| RULE_NOT_FOUND / ROOM_NOT_FOUND / ROOM_FULL | Không thể thực hiện yêu cầu; tiếp tục |
| NOT_OWNER / NOT_YOUR_TURN / ACTION_REJECTED | Kiểm tra server từ chối; tiếp tục |
| TIMEOUT | Deadline đã qua; không nhận action muộn |
| RATE_LIMITED | Quá tốc độ request; không xử lý request đó |
| INTERNAL_ERROR | Lỗi nội bộ; cancel trận nếu cần, không lộ chi tiết |

RequestId duy nhất trong một connection, không retry/replay. Tối đa 10,000 request/connection; server theo dõi ID đã dùng, đóng khi đạt quota sau response cuối. ID trùng là INVALID_PAYLOAD và không thực hiện lần hai. Client muốn reconnect phải HELLO mới, không resume. Display name không phải identity và có thể trùng.

## State machines

```text
Connection: CONNECTED --HELLO hợp lệ--> ACTIVE --QUIT/error/disconnect--> CLOSING --> CLOSED
Session: LOBBY --create/join--> IN_ROOM --start--> IN_MATCH
         IN_ROOM --leave/room closed--> LOBBY
         IN_MATCH --finished/cancelled--> IN_ROOM
         any --disconnect--> CLOSED
Room: WAITING --start--> RUNNING --result/cancel--> FINISHED --cleanup--> CLOSED
      WAITING --owner leaves, members remain--> WAITING (transfer owner)
      WAITING --last member leaves/disconnects--> CLOSED
Match: CREATED --start--> RUNNING --rule finished--> FINISHED
       CREATED/RUNNING --engine failure/maxTurns/admin shutdown--> CANCELLED
```

Room FINISHED là tạm thời: gửi kết quả, đóng phòng và chuyển mọi session còn kết nối về LOBBY. Không tái sử dụng roomId/matchId. Engine chặn mọi action terminal. MaxTurns đạt sau action mà rule chưa finished thì CANCELLED reason `MAX_TURNS`; engine không tự chọn winners. Owner rời/disconnect WAITING thì chuyển owner cho thành viên còn lại tham gia sớm nhất theo thứ tự server chấp nhận; chỉ đóng/xóa phòng khi không còn thành viên. RUNNING chuyển qua rule.onDisconnect như mọi player. Match chạy phải có ít nhất 2 player và tuân thủ validateConfig/initialState của rule.

Chính sách chuyển host trên đã được người dùng xác nhận ở Phase 3, thay chính sách đóng phòng khi host rời của Phase 1. Không đổi version/framing/command/enum/schema. Chuyển host đi kèm ROOM_EVENT LEFT (status hiện tại WAITING hoặc RUNNING, players là số còn lại); thành viên cuối rời nhận CLOSED (players=0). Protocol hiện không công bố host identity và không có ROOM_INFO; client xem trạng thái phòng qua LIST_ROOMS/events. Phase 4 đã có gameplay runtime; registry production chỉ có descriptor không gameplay nên START_MATCH của descriptor vẫn trả INVALID_STATE.

Diễn giải runtime Phase 4 đã được xác nhận: turn bắt đầu 0, luật tuần tự chuyển lượt sau action; luật đồng thời có thể giữ cùng turn/deadline cho nhiều người, mỗi người chỉ submit thành công một lần trong lượt. Engine kiểm tra ACTION.turn nguyên tử với mutation. Generation token nội bộ không đi trên wire. Timeout đóng lượt quá hạn sau callback cho các active player chưa submit. Disconnect gọi rule.onDisconnect; không còn người kết nối thì CANCELLED reason NO_CONNECTED_PLAYERS; lỗi luật dùng RULE_ERROR; shutdown dùng SERVER_SHUTDOWN. Các reason là giá trị string của field hiện có, không thêm enum/field.

## Timeout và transport lifecycle

- HELLO hoàn tất trong 5 giây từ accept. Timeout gửi ERROR TIMEOUT nếu có request hợp lệ, sau đó đóng.
- Idle 60 giây từ frame hợp lệ cuối. Client gửi PING mỗi 20 giây khi idle. PING không kéo dài lượt.
- Một frame phải hoàn tất trong 10 giây từ byte đầu: deadline tổng, không reset theo mỗi byte. SO_TIMEOUT đơn thuần chưa đủ để chống slow sender.
- Turn theo GameConfig. Server dùng thời gian monotonic; deadlineEpochMillis chỉ để client hiển thị. `now >= deadline` là quá hạn. Scheduler và action được serialize; callback cũ không tác động lượt mới.
- Client connect timeout 5 giây, response timeout 10 giây; response quá hạn đóng socket để tránh request chưa rõ kết quả bị retry. Event có thể xen giữa response, client phải demultiplex requestId.
- EOF/reset/timeout đều cleanup một lần, hủy writer/scheduler liên quan. Shutdown ngừng accept, hủy trận và đóng mọi socket trong giới hạn 10 giây.
- Vượt maxConnections 256: đóng socket mới. Rate 20 request/giây burst 40. Outbound 256 frame, write deadline 10 giây; disconnect slow client.

Kiểm tra server bắt buộc theo thứ tự: frame → version/envelope/direction → handshake/session → requestId → payload → state/membership/owner → match/turn/deadline → GameRule validation → mutation. Response/event chỉ phát sau mutation thành công. Network xử lý framing/lifecycle; adapter và competition/engine xử lý phần còn lại.
