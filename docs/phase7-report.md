# Phase 7 report — Web Multiplayer & Vercel Preparation

Hoàn thành ngày 12/10/2026, workspace D:\Socket\BattleCode. Chỉ Phase 7; không commit/push, không Phase 8, không deployment công khai. Các bổ sung nằm trong bốn nhóm API/architecture người dùng đã phê duyệt. Engine và hai production GameRules không sửa.

## Kết quả

TCP CLI/Bot và WebSocket browser dùng chung một Lobby, MatchManager, GameEngine, RuleRegistry, dispatcher, routes, ConnectionManager và ordered event publisher. React có Welcome, Lobby, Create/Join, Room/host migration, RPS, Number Guessing và Result. Cấu hình chuẩn bị Vercel frontend và Java backend riêng đã có; chưa có public URL hoặc kiểm thử WSS production.

## Cây thư mục bổ sung chính

```text
competition/src/main/java/dev/battlecode/competition/
  RoomDetails.java
  Lobby.java / RoomNotification.java / InMemoryLobby.java (sửa)
server/src/main/java/dev/battlecode/server/
  WebSocketConfig.java
  WebSocketGateway.java
  WebSocketConnection.java
  WebRoomConnection.java
  WebJsonCodec.java
  RoomTcpServer.java / RoomEventPublisher.java / ServerMain.java (sửa)
server/src/test/java/dev/battlecode/server/
  WebMultiplayerIntegrationTest.java
  WebSocketResourceTest.java
frontend/
  package.json / pnpm-lock.yaml / pnpm-workspace.yaml
  tsconfig.json / vite.config.ts / vercel.json
  index.html / .env.example / .gitignore
  src/
    main.tsx / App.tsx / model.ts / transport.ts / style.css
    App.test.tsx / Multiplayer.test.tsx / model.test.ts / transport.test.ts
scripts/start-web.ps1
docs/
  phase7-design.md / websocket-protocol.md / web-deployment.md
  phase7-report.md / architecture.md
  screenshots/phase7-rps-result.jpg
  screenshots/phase7-number-running.jpg
  screenshots/phase7-number-result.jpg
```

File sửa thêm: server/pom.xml (Jetty/Jackson/logging), network/.../DefaultTcpServer.java (watchdog chỉ cast TCP handler), competition/.../InMemoryLobbyTest.java (2 tests snapshot), README.md, docs/phases.md. Runtime .env.local, target/dist/node_modules và Maven cache là local/generated, không source cần publish. Final Maven logs: phase7-clean-test.log, phase7-package.log; exploratory phase7 logs không đại diện cho final verification.

## Dependency và API

```text
React browser → WebSocket adapter ┐
                                 ├→ một dispatcher → Lobby / MatchManager → GameEngine → GameRule
TCP CLI/Bot → TCP adapter ────────┘
server → network / protocol / competition / rules-api / rules-impl
competition → engine / rules-api
engine / rules-impl → rules-api
network / client → protocol
```

Jetty 12.1.14 và Jackson 2.22.0 chỉ nằm trong server; Java 21 giữ nguyên. Frontend ngoài Maven, React 19.3.0, TypeScript 5.9.3, Vite 7.3.7, Vitest 3.2.7 theo pnpm lockfile. Node đã test 24.19.0, pnpm 11.25.0. Không circular dependency hoặc hệ thống phòng browser riêng.

- RoomDetails(roomId,ruleId,status,hostId,players,capacity,revision), players chỉ playerId/nickname. List immutable; roomDetails(sessionId) capture dưới lifecycle lock, chỉ trả phòng session đang tham gia. LOBBY empty; session unknown/closed bị từ chối.
- RoomNotification.details chụp tại mutation; constructor/accessors 5 fields cũ giữ nguyên, equality/hashCode dùng các fields legacy. TCP ROOM_EVENT payload không đổi.
- RoomTcpServer thêm constructor (NetworkConfig,RuleRegistry,WebSocketConfig), websocketPort(); constructors cũ TCP-only. Không thêm overload 2 tham số gây ambiguity với registry=null.
- WebSocketConfig gồm bindAddress/port/allowedOrigins/outboundCapacity/writeTimeoutMillis. WebSocketConnection public để Jetty reflection truy cập listener; constructor vẫn package-private, không là domain API mới.
- Không sửa GameEngine/GameRule/MatchManager contracts hoặc Command/ErrorCode/TCP fields.

## Protocol, privacy và concurrency

Web JSON web-1 trên /ws: request chỉ requestId/command/payload string map; gateway inject TCP version1.0 và session của connection. HELLO ACK cấp playerId/sessionId. Origin exact allowlist, missing Origin HTTP403, binary/malformed/duplicate keys/extra identity/oversize bị đóng; 64KiB UTF-8 và MessageCodec budget. Existing dispatcher kiểm tra schema/dedup/rate/quota/host/membership/expectedTurn.

ROOM_STATE browser-only: envelope recipient/session, roomId/revision và room chứa hostId, public roster, ruleId/status/capacity. Current members nhận projection captured; người vừa rời/CLOSED nhận null. Không socket metadata, private GameState, pending RPS của đối thủ hoặc Number secret. Initial snapshot sau HELLO; mutation ROOM_EVENT → ROOM_STATE; start ROOM_STATE RUNNING → MATCH_EVENT STARTED; terminal MATCH_EVENT → CLOSED → room=null. ACK có thể xen giữa events; requestId correlate.

Competition lock giữ nguyên transaction; snapshot không lookup muộn trong worker. Publisher enqueue dưới lock, transport writer chạy ngoài business lock. TCP+WS chung maxConnections, HTTP ConnectionLimit, Jetty max32 threads, virtual tasks giới hạn theo connection admission/demand. Jetty internal continuation queue dùng mặc định; outbound application queue bounded 256, một async write, write deadline10s. HELLO/idle theo NetworkConfig. Overflow/slow client force disconnect, cleanup idempotent. Shutdown cancel match theo policy cũ rồi close/await hai transports và services; terminal delivery best effort.

Frontend giữ server-authoritative turn/deadline/views/winners/reason, chỉ own turn hoặc own simultaneous submit; ref lock chặn double-submit và ACTION gửi expected turn. Partial simultaneous STATE cùng turn vẫn áp dụng; stale room revision/stale turn/foreign match/late terminal state bị bỏ. CLOSED không xoá Result. Request timeout10s đóng session, PING20s; không tự retry/reconnect/resume. Manual HELLO mới reset room/match cũ. Không thêm resync API; ordered live stream + fail-close khi mất event/overflow, người dùng kết nối lại session mới.

## Kiểm thử thực tế

Final commands ngày 12/10/2026:

| Lệnh | Kết quả |
|---|---|
| mvn clean test | BUILD SUCCESS, 44.887s, kết thúc 18:08:57 +07 |
| mvn package | BUILD SUCCESS, 47.858s, kết thúc 18:09:48 +07 |
| pnpm install --frozen-lockfile | Thành công sau explicit esbuild allowBuilds |
| pnpm test | 13 tests / 4 files PASS, không failure |
| pnpm build | TypeScript + Vite production build PASS, 1.70s |

JUnit aggregate từ TEST-*.xml: **186 tests, 0 failures, 0 errors, 0 skipped**; giữ 175 regression Phase1–6, thêm 11 Phase7.

| Module | Tests |
|---|---:|
| rules-api | 3 |
| protocol | 11 |
| engine | 33 |
| competition | 13 |
| network | 2 |
| rules-impl | 16 |
| client | 9 |
| server | 99 |

11 tests mới: 2 member-authorized immutable/event-time/concurrent snapshot tests; 7 WS loopback integration tests; 2 codec/resource tests. Integration kiểm tra HELLO, health/Origin sai và thiếu, host identity với nickname giống nhau, host migration, outsider isolation, stale expectedTurn, simultaneous concurrent TCP+WS ACTION, RPS privacy, Number sequential/hints/no running secret, disconnect, cancellation-before-CLOSED, malformed identity, oversized messages, handshake timeout và shutdown. Resource test dùng stalled async send để làm đầy outbound queue, xác minh disconnect/manager cleanup; timeout/shutdown sockets và executor đều có deadline hữu hạn. Existing TCP CLI/Bot/benchmark/engine/rules regressions chạy trong full reactor.

13 frontend tests: identity/manual new-session reset, stale revisions/turns/foreign match, same-turn STATE, terminal/CLOSED, HTTPS→WSS validation, socket correlation/timeouts/cleanup, nickname validation, host migration không dựa nickname, double-submit/expectedTurn, Number guessing disabled ngoài lượt và cancellation Result. Fake transport chỉ ở test scope.

Hai browser session thật trên localhost đã đi hết HELLO → lobby → CREATE/JOIN → START → ACTION → FINISHED → lobby cho RPS và Number Guessing. Đã thấy own RPS choice mà đối thủ chưa thấy, host migration khi host LEAVE rồi rejoin, Number Guessing chuyển lượt/hint và secret=2 chỉ ở terminal result. Console cả hai tab không error lúc gameplay. Screenshot lưu trong docs/screenshots. Process backend/Vite demo đã dừng sau kiểm thử để giải phóng JAR Windows trước final Maven clean.

Trong quá trình triển khai có lỗi Jetty listener reflection visibility và Origin-null lookup, đã sửa và test. Một lần clean bị Windows lock do demo đang giữ JAR; dừng demo rồi cả clean test/package final đều SUCCESS. Không skip tests để vượt các lỗi này.

## Chạy và triển khai

```powershell
cd D:\Socket\BattleCode
mvn package
./scripts/start-web.ps1
# Terminal khác:
cd frontend
Copy-Item .env.example .env.local
pnpm install --frozen-lockfile
pnpm dev --port 5173 --strictPort
```

Mở http://localhost:5173 trong hai tab. TCP port script=9000, WS=8081. CLI/Bot Phase1–6 trỏ TCP9000 để tham gia phòng browser. Script Start-Web hỗ trợ BindIp/ports/Origins. Chạy ServerMain như README cũ không BATTLECODE_WS_PORT vẫn TCP-only.

LAN: WS URL phải dùng IP backend thật, Origin allowlist exact frontend LAN URL; bind0.0.0.0 khi cần. Production: Vercel root frontend, frozen pnpm install/build/dist, VITE_WS_URL=WSS, Java21 process riêng và TLS reverse proxy/health/shutdown. Chi tiết, môi trường và checklist ở web-deployment.md.

## Giới hạn và bàn giao

Đã hoàn thành runtime/giao diện/tests/tài liệu và cấu hình chuẩn bị deploy trong phạm vi Phase7. Chưa deploy công khai, chưa xác minh hai thiết bị LAN vật lý hoặc WSS/Internet production. Đây là localhost verification, không phải capacity benchmark hay production SLA. Session/room/match còn in-memory, không resume/persistence; các giới hạn Engine/result retention Phase4–6 giữ nguyên. Danh sách lobby dùng nút refresh; không thêm broadcast metadata phòng cho outsiders hoặc resync API.

Không commit/push, không triển khai Phase8. Operator tiếp tục theo checklist deployment khi có backend domain, frontend origin và môi trường hosting thực tế.
