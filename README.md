# Mini Battlecode Platform — Phase 7: Web Multiplayer

Java 21, Maven multi-module và TCP Socket thuần Java, bổ sung WebSocket gateway Jetty và frontend React/TypeScript. TCP CLI, bot và browser dùng chung lobby/room, game engine và hai production game: `rock-paper-scissors`, `number-guessing`. Không Spring Boot hoặc database.

## Chạy web multiplayer

```powershell
mvn package
./scripts/start-web.ps1
# Terminal khác, yêu cầu Node 22.12+ và pnpm 11.25.0:
cd frontend
Copy-Item .env.example .env.local
pnpm install --frozen-lockfile
pnpm dev --port 5173 --strictPort
```

Mở http://localhost:5173 trong hai tab, nhập hai nickname, Create → Join → host Start. Backend web /ws và /health trên port 8081; script chạy TCP trên 9000. CLI/bot có thể tham gia cùng phòng qua TCP 9000. Start vẫn được server kiểm tra bằng Room.ownerId. Không tự reconnect/resume.

`pnpm test` và `pnpm build` chạy tại frontend. Chuẩn bị Vercel (frontend root, VITE_WS_URL=WSS) và backend Java riêng: [web-deployment](docs/web-deployment.md). Đặc tả: [WebSocket](docs/websocket-protocol.md); bàn giao: [Phase 7 report](docs/phase7-report.md). Chưa deploy công khai hoặc xác minh LAN hai thiết bị vật lý.

## Build và kiểm thử

Yêu cầu JDK 21, Maven 3.9+. Tại thư mục dự án:

```powershell
Set-Location D:\Socket\BattleCode
mvn clean test
mvn package
```

Test sử dụng JUnit 5, TCP loopback và port động, timeout hữu hạn và cleanup socket/thread. Test slow reader Phase 2 mất khoảng 10 giây. Maven cache nằm trong `.maven-repository/` (gitignore); lần đầu cần tải dependency. Nếu sandbox chặn socket, cần cho phép TCP loopback khi chạy Maven, không skip test.

Phase 4 baseline: 146 tests PASS (85 regression + 61 Phase 4). Phase 5 adds 14 rule unit tests and 7 production TCP integration tests. Final full-reactor verification: 167 tests PASS, no failures/errors/skips, and both `mvn clean test` and `mvn package` BUILD SUCCESS. Logs: `phase5-clean-test.log`, `phase5-package.log`; see [phase5-report](docs/phase5-report.md).

`mvn package` tạo JAR cho 8 module và copy runtime dependency vào `client/target/dependency/` và `server/target/dependency/`. Client dùng JLine 4.4.3 classifier jdk11 cho Java 21, chỉ xử lý terminal; transport vẫn dùng Socket thuần Java. JLine `printAbove` giữ dòng đang gõ khi hiện event; stdin/stdout qua pipe dùng console dạng stream. Tham khảo [printAbove](https://jline.org/docs/examples/print-above/) và [classifier cho Java 11–21](https://jline.org/docs/modules/terminal-providers/).

## Chạy server — Terminal 1

```powershell
Set-Location D:\Socket\BattleCode
java -cp "server\target\server-1.0.0-SNAPSHOT.jar;server\target\dependency\*" dev.battlecode.server.ServerMain 127.0.0.1 7777
```

ServerMain dùng composition root RoomTcpServer với Lobby, MatchManager, GameEngine và registry production gồm `room-only-no-gameplay`, `rock-paper-scissors`, `number-guessing`. Descriptor room-only không hỗ trợ START_MATCH; hai game còn lại dùng chung engine/manager. Ctrl+C hủy match đang chạy với SERVER_SHUTDOWN và cleanup connection/session/room/event worker/scheduler.

Tham số tùy chọn giữ nguyên Phase 2:

```text
ServerMain [bindIp] [port] [maxConnections] [helloMs] [idleMs] [frameMs]
Defaults: 127.0.0.1 7777 256 5000 60000 10000
```

Port 0 để OS cấp port, xem port thực tế trong banner. JAR cần classpath, không phải executable/fat JAR.

## Chạy client — Terminal 2 và 3

Terminal 2, Client A:

```powershell
Set-Location D:\Socket\BattleCode
java -cp "client\target\client-1.0.0-SNAPSHOT.jar;client\target\dependency\*" dev.battlecode.client.CliClient 127.0.0.1 7777 Client-A
```

Terminal 3, Client B:

```powershell
Set-Location D:\Socket\BattleCode
java -cp "client\target\client-1.0.0-SNAPSHOT.jar;client\target\dependency\*" dev.battlecode.client.CliClient 127.0.0.1 7777 Client-B
```

Bỏ arguments để nhập IP/port/name theo prompt. Client gửi HELLO, hiển thị session ID rồi giữ vòng nhập lệnh. Event được hiển thị ngay khi nhận, kể cả đang chờ nhập; terminal tương tác vẽ lại dòng hiện tại thay vì ghi đè lệnh đang gõ. Nếu bị disconnect trong lúc console đang chờ thì Enter kết thúc vòng console.

| CLI | Chức năng |
|---|---|
| `list_rules [offset] [limit]` | Danh sách registry thực tế, mặc định offset=0/limit=10 |
| `list_rooms [offset] [limit]` | Danh sách phòng: id, ruleId, status, count, capacity |
| `create_room <ruleId> [maxPlayers] [maxTurns] [turnTimeoutMillis] [option=value ...]` | Tạo phòng; defaults 2/100/1000. Hai game yêu cầu maxPlayers=2 |
| `join_room <roomId>` | Join bằng UUID phòng server cấp |
| `leave_room` | Rời phòng |
| `start_match <roomId>` | Host bắt đầu trận nếu rule có gameplay và đủ player |
| `action <matchId> <turn> [key=value ...]` | Gửi action.<key> theo luật; turn lấy từ MATCH_EVENT |
| `ping` | Kiểm tra connection |
| `help` | Rule IDs, config/action keys, cách dùng turn/deadline và event/error |
| `exit` | QUIT → ACK rồi disconnect |

Không có ROOM_INFO hoặc host identity trong wire protocol; nhập room_info chỉ nhắc dùng list_rooms. CLI hiển thị ACK/ERROR, ROOM_EVENT và MATCH_EVENT theo schema 1.0. Hai rule validate payload phía server; client không thể gửi winners hoặc state.

## Production game rules

### Rock Paper Scissors

`roundsToWin` mặc định 3, cho phép 1..10. Cả hai người chơi submit đồng thời một lần mỗi lượt; MATCH_EVENT báo submitted nhưng giấu choice cho đến round reveal. Hòa không cộng điểm. Khi một người disconnect, người còn lại thắng do bỏ cuộc. Khi hết deadline, người đã submit thắng round; nếu cả hai bỏ lượt, round hòa. Kết quả round cuối cùng được công bố trong reason của FINISHED vì schema 1.0 không có trường terminal view.

```text
# Client A
list_rules
create_room rock-paper-scissors 2 100 1000 roundsToWin=3

# Client B
join_room <roomId>

# Client A, after the join event
start_match <roomId>
# Copy matchId from ACK; both players use turn=0 until both choices arrive.
action <matchId> 0 choice=ROCK

# Client B
action <matchId> 0 choice=SCISSORS
```

### Number Guessing

Secret sinh ngẫu nhiên phía server trong inclusive range mặc định 1..100. `minNumber`/`maxNumber` tùy chọn phải tạo range 2..1,000,000 giá trị, không overflow. Hai người lần lượt đoán; gợi ý `HIGHER`, `LOWER`, `CORRECT`. Public view giấu secret, chia sẻ guess history gần nhất (tối đa 28 lượt để vừa view budget). Khi đoán đúng, secret được công bố trong reason của FINISHED. Timeout bỏ lượt; `MAX_TURNS` dùng policy cancel Phase 4, không tự chọn winner.

```text
# Client A
create_room number-guessing 2 100 1000 minNumber=1 maxNumber=100

# Client B
join_room <roomId>

# Client A, after the join event; both actions follow the turn in MATCH_EVENT
start_match <roomId>
action <matchId> 0 guess=<integer>

# The current player alternates after an incorrect guess or timeout.
action <matchId> <turn from latest STATE> guess=<integer>
```

`MATCH_EVENT` hiển thị turn, deadline và view riêng; đọc ACK/ERROR để biết action được chấp nhận hay vì sao bị từ chối.

### Hai room đồng thời

Tạo một room cho mỗi rule với hai cặp client khác nhau, start cả hai, rồi gửi action. Mỗi match có UUID, config, state, deadline và winner riêng. TCP integration test xác minh event/state không cross-room, match ID không dùng chéo, và disconnect/finish ở room này không ảnh hưởng room kia.

Đã chạy CLI production cùng lúc với bốn process client và server JAR: RPS kết thúc sau `ROCK`/`SCISSORS` với reveal/winner đúng; Number Guessing trong range 1..2 hoàn tất qua TCP; kiểm tra output từng client chỉ chứa MATCH_EVENT cho match thuộc room của nó. Một demo CLI Number Guessing riêng dùng secret mặc định và thực sự quan sát HIGHER/LOWER cho đến khi đoán đúng. Match IDs, các lệnh và chuỗi hint thực tế nằm trong [phase5-report](docs/phase5-report.md).

Engine dùng per-match lock, expectedTurn nguyên tử và generation token chống timeout cũ. MatchManager dùng khóa lifecycle chung với Lobby; socket I/O ở worker riêng. LEAVE_ROOM khi RUNNING bị từ chối; disconnect giao cho rule, host chuyển theo thứ tự join; terminal đóng phòng và đưa session còn kết nối về LOBBY. Snapshot chỉ chứa view riêng. Xem [kiến trúc](docs/architecture.md) và [báo cáo Phase 4](docs/phase4-report.md) cho API, concurrency và giới hạn.

## Demo bắt buộc — ROOM-01

`ROOM-01` là nhãn trong kịch bản, không phải roomId literal hay tên trên wire. Room ID là UUID server sinh, cần copy từ ACK CREATE_ROOM.

1. Terminal 1 chạy server như trên.
2. A nhập:

   ```text
   list_rules
   create_room room-only-no-gameplay 2 100 1000
   ```

   A nhận ACK có `roomId=<UUID>` và ROOM_EVENT CREATED với players=1. Gọi UUID này là ROOM-01.
3. B nhập:

   ```text
   list_rooms
   join_room <UUID của ROOM-01>
   ```

   B thấy phòng WAITING, players=1/maxPlayers=2; join nhận ACK. A và B nhận ROOM_EVENT JOINED, players=2. Có thể chạy list_rooms để xem snapshot mới.
4. B nhập `leave_room`: ACK; A nhận ROOM_EVENT LEFT, players=1 ngay khi đang chờ nhập.
5. A nhập `leave_room`: ACK và ROOM_EVENT CLOSED, players=0. B nhập `list_rooms`: count=0, nextOffset=-1; ROOM-01 đã bị xóa.
6. Cả hai nhập `exit`; Ctrl+C dừng server.

Demo chuyển host riêng: A tạo phòng, B join, A leave/exit. Phòng vẫn WAITING với B làm host nội bộ. Host mới là thành viên còn lại tham gia sớm nhất; phòng chỉ bị xóa khi người cuối cùng rời. Schema 1.0 không công bố host identity, nên client xem thay đổi membership qua LEFT/list_rooms; test server xác minh ownerId.

## Hai máy cùng LAN

- Máy A xem IP bằng ipconfig, ví dụ 192.168.1.10; chạy server với bind IP đó hoặc 0.0.0.0:

  ```powershell
  java -cp "server\target\server-1.0.0-SNAPSHOT.jar;server\target\dependency\*" dev.battlecode.server.ServerMain 192.168.1.10 7777
  ```

- Cho phép inbound TCP 7777 trên Windows Firewall cho LAN. Hai máy cần route tới nhau, không bị AP client isolation.
- Máy B cài Java 21. Build repo hoặc copy `client/target/client-1.0.0-SNAPSHOT.jar` và toàn bộ `client/target/dependency/` vào một thư mục trên B. Tại đó:

  ```powershell
  java -cp "client-1.0.0-SNAPSHOT.jar;dependency\*" dev.battlecode.client.CliClient 192.168.1.10 7777 Client-B
  ```

- Trên B không dùng 127.0.0.1 để trỏ tới A. Thực hiện cùng kịch bản create/list/join/leave ở trên. LAN hai thiết bị chưa được kiểm chứng tự động; tests xác minh loopback và process CLI thật.

## Phase 6 — Automated bots and TCP benchmark

Bot clients run as independent client processes and use protocol 1.0 over TCP. They do not load server/game-engine code. Strategies: `rps` (seeded local choice) and `number-guessing` (midpoint from public range/history).

Join a room that another player has created and started:

```powershell
java -cp "client\target\client-1.0.0-SNAPSHOT.jar;client\target\dependency\*" dev.battlecode.client.BotMain 127.0.0.1 7777 Bot-A <roomId> number-guessing number-guessing
```

Run a complete two-bot demo against an already-running server:

```powershell
java -cp "client\target\client-1.0.0-SNAPSHOT.jar;client\target\dependency\*" dev.battlecode.client.BotDemoMain 127.0.0.1 7777 number-guessing
```

Run the bounded real-TCP benchmark (default 2 clients/1 room; larger profiles are explicit and capped at 50 clients):

```powershell
java -cp "client\target\client-1.0.0-SNAPSHOT.jar;client\target\dependency\*" dev.battlecode.client.BenchmarkMain 127.0.0.1 7777 2 30
```

For LAN testing, follow [the two-device checklist](docs/lan-testing.md) and run `scripts\lan-preflight.ps1 -ServerAddress <server-ip> -Port 7777`. Phase 6 loopback tests do not verify two separate machines. See [benchmark details](docs/benchmark.md) and [Phase 6 report](docs/phase6-report.md).

## Cấu trúc và dependency

```text
BattleCode/
├── pom.xml
├── rules-api/    # GameConfig, GameRule, RuleRegistry
├── protocol/     # Codec và wire schema 1.0 giữ nguyên
├── engine/       # DefaultGameEngine, private snapshot, errors
├── competition/  # InMemoryLobby, DefaultMatchManager, domain notifications
├── network/      # TCP runtime Phase 2, không phụ thuộc competition
├── rules-impl/   # RoomOnlyRule, RockPaperScissorsRule, NumberGuessingRule
├── client/       # CLI, TCP client, terminal/event rendering
├── server/       # Composition, protocol adapter, ordered room/match publisher
└── docs/         # Architecture/protocol/phase reports
```

```text
server → network, protocol, competition, rules-api, rules-impl
network → protocol
client → protocol (+ JLine cho terminal)
competition → engine, rules-api
engine, rules-impl → rules-api
server --test only--> client
```

Session được server sinh sau HELLO và gắn socket; không account auth/JWT/resume. Lobby/MatchManager dùng một ReentrantLock cho lifecycle, Engine có khóa riêng mỗi trận. Notifications enqueue nonblocking và gửi theo thứ tự bởi worker server riêng. Host transfer và API mở rộng Phase 4 đã được người dùng phê duyệt; giữ phương thức cũ, wire schema/command/enum/version. Timeout transport, framing, rate/quota và admission Phase 2 giữ nguyên.

Đọc [protocol](docs/protocol.md), [kiến trúc](docs/architecture.md), [báo cáo Phase 4](docs/phase4-report.md) và [báo cáo Phase 5](docs/phase5-report.md). docs/phases.md ghi kế hoạch ban đầu và phạm vi đã được người dùng thay thế. Không thực thi bot/source không tin cậy.

## Phase 7 — kết quả xác minh

186 JUnit tests PASS (175 regressions + 11 mới), 0 failures/errors/skips; `mvn clean test` và `mvn package` BUILD SUCCESS. React có 13 frontend tests PASS và production build PASS. Hai browser session localhost hoàn thành cả hai trò chơi, host migration và privacy. [Báo cáo Phase 7](docs/phase7-report.md) có API, ordering, file changes, logs và screenshot. Local demo đã dừng; public deployment và LAN hai thiết bị chưa VERIFIED.

## Phase 7D — chuẩn bị deploy miễn phí

WEB_ONLY chỉ mở HTTP/WebSocket trên `0.0.0.0:$PORT`, dùng chung services hiện có; LOCAL giữ TCP + WS. Docker Java 21 có giới hạn heap và frontend production build yêu cầu WSS URL rõ ràng. Xem [hướng dẫn Render/Vercel](docs/free-deployment.md) và [báo cáo Phase 7D](docs/phase7d-report.md). Chưa push, build Docker image hoặc deploy tài khoản người dùng.
