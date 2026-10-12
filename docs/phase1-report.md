# Báo cáo Phase 1

Ngày nghiệm thu: 11/10/2026 (Asia/Bangkok). Môi trường JDK Temurin 21.0.12.1, Maven 3.9.16. Khảo sát ban đầu: thư mục trống, không Git và không AGENTS.md tại workspace hoặc thư mục cha được kiểm tra. Không commit/push.

## Cây thư mục

```text
BattleCode/
├── pom.xml
├── .gitignore
├── .mvn/maven.config
├── README.md
├── rules-api/{pom.xml,src/main/java,src/test/java}
├── protocol/{pom.xml,src/main/java,src/test/java}
├── engine/{pom.xml,src/main/java}
├── competition/{pom.xml,src/main/java,src/test/java}
├── network/{pom.xml,src/main/java}
├── rules-impl/{pom.xml,src/main/java}
├── client/{pom.xml,src/main/java}
├── docs/{architecture.md,protocol.md,phases.md,phase1-report.md}
├── phase1-test.log
└── phase1-package.log
```

Các thư mục target và Maven cache được bỏ qua trong cây trên.

## Dependency

```text
network     -> protocol
client      -> protocol
competition -> engine, rules-api
engine      -> rules-api
rules-impl  -> rules-api
protocol, rules-api -> chỉ JDK (production)
```

JUnit 5 chỉ ở test scope. Kiểm tra POM và imports xác nhận network không biết luật và không có cycle. Phase 2 thêm composition root server, không thay ranh giới network.

## Interfaces

| Module | Interface |
|---|---|
| network | TcpServer, ConnectionHandler, ConnectionManager, RequestDispatcher |
| protocol | MessageCodec |
| competition | Lobby, MatchManager |
| engine | GameEngine |
| rules-api | GameRule, RuleState, RuleRegistry |
| client | TcpClient, BotClient, BotStrategy |

GameRule cung cấp validateConfig, initialState, currentPlayer, isActionAllowed, applyAction, onTurnTimeout, onDisconnect, evaluate và viewFor. Registry immutable từ tập luật tin cậy; không nạp code client. Models Room, PlayerSession, GameConfig, GameState, GameResult, RuleOutcome và Message dùng record, defensive copy cho collection.

## Protocol

Version 1.0: length-prefix int32 big-endian, body tối đa 65,536 byte, string UTF-8 có uint16 byte length, envelope version/requestId/sessionId/command/errorCode và payload map. Có HELLO, ACK/ERROR, các command lobby/match, events, quy định timeout, session binding, validation server, state machine và disconnect. Chi tiết normative trong protocol.md; codec thực thi phần framing/cấu trúc, service Phase 2 thực thi phần lifecycle/ngữ nghĩa.

## Kết quả kiểm thử

| Suite | Tests | Failures / Errors / Skipped |
|---|---:|---|
| GameConfigTest | 3 | 0 / 0 / 0 |
| MessageCodecTest | 11 | 0 / 0 / 0 |
| RoomTest | 3 | 0 / 0 / 0 |
| Tổng | 17 | 0 / 0 / 0 |

`mvn test`: BUILD SUCCESS toàn reactor, 10.546 giây trên source cuối cùng.
`mvn package`: BUILD SUCCESS toàn reactor, 17.822 giây; chạy lại cùng 17 test và tạo 7 module JAR. rules-impl là JAR dự phòng chưa có implementation. Logs tại phase1-test.log và phase1-package.log; Surefire XML nằm dưới target/surefire-reports của ba module có test.

Coverage hành vi: Unicode roundtrip; frame phân mảnh/ghép; clean/truncated EOF; length âm/0/quá lớn; oversized write không xuất partial frame; UTF-8 sai; duplicate key; unknown command; trailing bytes; consistency envelope; config limits/copy; outcome chưa kết thúc; registry rỗng; capacity/ownership và immutable room snapshots; session name.

Lần chạy đầu bị hạn chế quyền ghi user Maven cache; đã chuyển cache vào workspace. Download dependency cần quyền mạng mở rộng trong môi trường này; sau khi tải, mvn test chạy thành công với quyền mặc định. Đây không phải lỗi source.

## Hoàn thành và phần tiếp theo

Hoàn thành toàn bộ bộ khung Phase 1, 7 module, model/interfaces, registry, codec, tài liệu kiến trúc/protocol/kế hoạch và test/package. Không có luật chơi, frontend, database hoặc thực thi source không tin cậy.

Chưa triển khai TCP accept/read/write runtime, session/lobby/match services, engine orchestration, CLI TCP và bot loop; đây là hạng mục Phase 2. Không coi marker CLI hoặc interface là server/client đã chạy. Chưa có integration test socket hay load test vì chưa có runtime.

Phase 2 theo phases.md: composition root và adapter trước, TCP virtual threads và bounded writer, handshake/session validation, lobby/match serialization, engine deadlines, CLI/bot và integration tests loopback. Luật thật bắt đầu Phase 3 sau khi được chọn rõ.
