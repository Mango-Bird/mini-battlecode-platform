# WebSocket protocol — web-1

Đã được người dùng phê duyệt. TCP protocol 1.0 giữ nguyên framing, commands, enums và wire fields. Web endpoint /ws dùng UTF-8 text JSON; production dùng WSS qua TLS reverse proxy.

## Limits và validation

Mỗi message tối đa 65,536 bytes UTF-8; Jetty cũng giới hạn text message length. Binary bị đóng; không permessage-deflate. Parser strict duplicate detection, nesting tối đa 4, không trailing JSON, payload string map tối đa 128 fields và phải qua budget MessageCodec TCP. Extra/missing envelope keys, unknown command, sai JSON/type hoặc oversized message đóng connection. Payload/schema/requestId hợp lệ về framing nhưng sai nghiệp vụ nhận ERROR qua dispatcher hiện có.

## Request

```json
{"requestId":"r1","command":"HELLO","payload":{"name":"Alice"}}
```

Chỉ ba envelope keys này. requestId 1..64 ASCII [A-Za-z0-9_-], duy nhất suốt connection; quota 10,000 decoded requests. Payload values đều string. Các command và payload giữ đúng docs/protocol.md: HELLO, PING, LIST_RULES/LIST_ROOMS (offset/limit), CREATE_ROOM (ruleId/maxPlayers/maxTurns/turnTimeoutMillis/option.*), JOIN_ROOM, LEAVE_ROOM, START_MATCH, ACTION (matchId/turn/action.*), QUIT. Không arbitrary room lookup hoặc reconnect/resume command.

Gateway inject version=1.0 và connection-bound sessionId. Browser không gửi version/sessionId/playerId/hostId; giả mạo envelope bị đóng, identity payload ngoài schema bị ERROR. HELLO lặp nhận INVALID_STATE, không tạo session mới. Host authorization dùng Room.ownerId; nickname không cấp quyền.

## Response và existing events

```json
{"type":"response","requestId":"r1","sessionId":"server-uuid","command":"ACK","errorCode":"NONE","payload":{"for":"HELLO"}}
```

ACK/ERROR ánh xạ Message cũ. ERROR payload.message được sanitize bởi dispatcher, không stack trace. SessionId đồng thời là playerId do server cấp. Request không tin identity từ browser. Event có type=event, requestId rỗng, recipient sessionId, command ROOM_EVENT/MATCH_EVENT và errorCode NONE.

ROOM_EVENT giữ nguyên type/roomId/status/players. MATCH_EVENT STARTED/STATE gồm matchId/turn/deadlineEpochMillis/view.* riêng cho recipient. FINISHED gồm winners/reason, CANCELLED reason. Không serialize RuleState. RPS yourChoice chỉ lựa chọn của recipient; lastChoice.* chỉ sau reveal. Number Guessing running view không có secret. Secret chỉ được hiển thị nếu terminal reason công bố CORRECT_GUESS;secret=... theo rule cũ. Frontend không tính winner hoặc terminal score.

## Browser-only ROOM_STATE

```json
{"type":"room","command":"ROOM_STATE","sessionId":"recipient-uuid","roomId":"room-uuid","revision":2,"room":{"roomId":"room-uuid","ruleId":"rock-paper-scissors","status":"WAITING","hostId":"host-uuid","capacity":2,"revision":2,"players":[{"playerId":"host-uuid","nickname":"Alice"},{"playerId":"recipient-uuid","nickname":"Bob"}]}}
```

Full room chỉ current members ở thời điểm snapshot; players có đúng playerId/nickname. Không socket metadata/config secrets/private game state/pending choices/secret number. Browser so sessionId với hostId để trình bày host; server vẫn authorize mọi START_MATCH.

room=null khi recipient đã rời phòng hoặc phòng CLOSED; envelope vẫn giữ roomId/revision để bỏ event cũ mà không xoá nhầm phòng mới. HELLO ACK được flush trước initial ROOM_STATE: roomId=null, revision=0, room=null. room revision bắt đầu 1, tăng trên JOIN/LEAVE/disconnect/host migration/RUNNING/CLOSED. So revision trong phạm vi cùng roomId.

## Thứ tự và snapshot consistency

- Một ordered publisher dùng chung TCP/WS. Snapshot RoomNotification được chụp nguyên tử dưới lifecycle lock ngay tại mutation, không lookup trạng thái mới trong worker.
- CREATE/JOIN/LEAVE/host migration: ROOM_EVENT rồi ROOM_STATE cùng batch. Response ACK có thể xen giữa event/snapshot; correlate requestId, không giả định ACK đến trước event.
- Người rời nhận room=null, thành viên còn lại nhận roster/host mới từ cùng snapshot. Outsider không nhận projection hoặc event. Lobby.roomDetails(sessionId) chỉ trả phòng đang tham gia; LOBBY trả empty, unknown/closed session bị từ chối.
- START_MATCH: ROOM_STATE RUNNING chụp trong transaction rồi MATCH_EVENT STARTED. Metadata phòng đã có trước gameplay event.
- Terminal: MATCH_EVENT FINISHED/CANCELLED, ROOM_EVENT CLOSED, ROOM_STATE room=null. Browser giữ Result sau CLOSED. CLOSED xóa phòng trên server và trả các session còn kết nối về lobby.
- ACTION ACK xác nhận expected turn đã chấp nhận; STATE chứa turn hiện tại. Simultaneous partial STATE có thể cùng turn, không được bỏ chỉ vì turn bằng nhau. Một action thành công/player/turn; browser khóa submit, server kiểm tra nguyên tử như Phase 4.

WebSocket giữ thứ tự trong connection. Revision và matchId/turn giúp bỏ stale room/foreign match/late terminal state; không đảm bảo delivery sau disconnect. Queue overflow/slow write đóng session để tránh tiếp tục trên luồng event thiếu. Không cần thêm resync API trong Phase 7: người dùng kết nối lại thủ công bằng HELLO, session mới; không resume room/match cũ. LIST_ROOMS refresh chỉ làm mới danh sách công khai, không khôi phục membership.

## Lifecycle và tài nguyên

HTTP upgrade (Origin exact allowlist) → CONNECTED → HELLO ACK → ACTIVE → QUIT/fatal error/EOF/timeout/shutdown → CLOSED. Origin thiếu hoặc ngoài allowlist bị HTTP 403. WS uses protocol1.0 internally; gửi trường version không hợp lệ về web envelope sẽ bị đóng, không thương lượng phiên bản mới.

Defaults: HELLO 5s, application idle 60s, client request deadline 10s, PING 20s, outbound queue 256, write deadline 10s. NetworkConfig có thể đổi HELLO/idle và WebSocketConfig có thể giảm queue/write để test. Control PING không reset application idle. Một inbound demand/task và một async writer/connection; event worker chỉ enqueue ngoài business lock. Global maxConnections dùng chung TCP+WS. Jetty 32 platform threads, HTTP ConnectionLimit, header limits và upgrade idle deadline; internal Jetty continuation queue dùng thiết kế mặc định, application outbound có giới hạn cứng.

QUIT/fatal response đã flush được đóng WS bình thường; malformed/slow client/watchdog được force disconnect. Cleanup dispatcher/session/room/match/manager idempotent trên application task. Graceful server shutdown cancel matches theo policy cũ rồi đóng cả transport, await worker/watchdog/tasks. Terminal delivery khi shutdown best effort. Client request timeout đóng connection, không retry ambiguous action, không reconnect tự động.
