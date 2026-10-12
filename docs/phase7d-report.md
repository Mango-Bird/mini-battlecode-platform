# Phase 7D — Free deployment preparation

Ngày xác minh: 2026-10-12. Phạm vi: chuẩn bị Vercel Hobby frontend và Render Free Java 21 WebSocket backend; không deploy, commit/push hoặc Phase 8.

## Thay đổi

| File | Nội dung |
|---|---|
| `server/.../ServerMode.java` | LOCAL / WEB_ONLY |
| `server/.../ServerLaunchConfig.java` | Parse args/env, mandatory PORT/Origin, limits cho WEB_ONLY |
| `server/.../ServerMain.java` | Chọn mode, giữ entrypoint cũ |
| `server/.../RoomTcpServer.java` | Constructor tương thích mới, không tạo TCP listener ở WEB_ONLY; cùng services, cleanup |
| `server/.../WebSocketConfig.java` | Invalid origin không có scheme trả IllegalArgumentException thay vì NullPointerException |
| `server/.../ServerLaunchConfigTest.java` | 4 tests config và local compatibility |
| `server/.../WebOnlyDeploymentIntegrationTest.java` | 5 tests startup/health/HELLO/Origin/two peers/games/shutdown |
| `Dockerfile`, `.dockerignore` | Multi-stage Maven/JDK21 → JRE21, nonroot, runtime artifacts, excluded env/secrets/caches |
| `.gitignore` | Ignore logs và env thực tế; giữ .env.example |
| `frontend/deployment-config.ts`, `frontend/vite.config.ts` | Production URL guard, không ship localhost |
| `frontend/src/deployment-config.test.ts` | 2 tests production URL |
| `frontend/.env.example` | Phân biệt local development và production WSS |
| `scripts/start-web.ps1` | Explicit LOCAL để không thừa hưởng WEB_ONLY từ terminal |
| `README.md`, `docs/architecture.md`, `docs/web-deployment.md` | Runtime mode, references và Corepack variable chính xác |
| `docs/free-deployment.md`, báo cáo này | Quy trình deploy do operator thực hiện và limitations |

`server/...` chỉ `server/src/main/java/dev/battlecode/server/` hoặc test tương ứng. Không sửa GameEngine, MatchManager, GameRule, hai production rules, TCP protocol version/schema/commands/enums. Không thêm Lobby/Engine khác; network I/O vẫn ngoài business locks.

## Environment và runtime

| Variable | Hợp đồng |
|---|---|
| `BATTLECODE_MODE` | LOCAL mặc định; WEB_ONLY bỏ TCP listener |
| `PORT` | Bắt buộc 1..65535 ở WEB_ONLY, do Render cấp; bind 0.0.0.0 |
| `BATTLECODE_ORIGINS` | Bắt buộc WEB_ONLY; exact HTTP(S) origins comma-separated, không wildcard/path |
| `BATTLECODE_WS_PORT`, `BATTLECODE_WS_BIND` | LOCAL giữ hành vi cũ; WEB_ONLY không dùng |
| `JAVA_TOOL_OPTIONS` | Docker mặc định heap192m/metaspace96m/direct64m/code48m/SerialGC/CPU1 |
| `VITE_WS_URL` | Production bắt buộc public WSS endpoint, không credentials/loopback |
| `ENABLE_EXPERIMENTAL_COREPACK` | Vercel đặt 1 để dùng packageManager pin pnpm11.25.0 |

WEB_ONLY admission16, outbound queue32/connection, write timeout10s; cùng parser/message limits/handshake/idle và privacy Phase7. Constructor cũ và LOCAL TCP-only/TCP+WS giữ nguyên. Health GET /health trả 200 status ok, không metadata riêng. Shutdown hủy match theo policy cũ, đóng transport và await workers; kiểm tra listener/session/connection cleanup, close idempotent. Không bảo đảm terminal event được giao sau socket đóng.

Lệnh PowerShell sau `mvn package`:

```powershell
$env:BATTLECODE_MODE='WEB_ONLY'
$env:PORT='10000'
$env:BATTLECODE_ORIGINS='http://localhost:5173'
java -cp 'server/target/server-1.0.0-SNAPSHOT.jar;server/target/dependency/*' dev.battlecode.server.ServerMain
```

Render dùng Docker ENTRYPOINT cùng class, classpath Linux `/app/server.jar:/app/lib/*`; không start override. Production Origin phải là exact Vercel URL. LOCAL: `./scripts/start-web.ps1`.

## Verification

- `mvn clean test`: BUILD SUCCESS, 195 JUnit tests (186 regressions + 9 mới), 0 failures/errors/skips. Logs `phase7d-clean-test.log`.
- `mvn package`: BUILD SUCCESS, chạy lại toàn bộ 195 tests, package server JAR và runtime dependencies. Logs `phase7d-package.log`.
- `pnpm test`: PASS, 15 tests/5 files (13 regressions + 2 mới). Logs `phase7d-frontend-test.log`.
- `pnpm build`: PASS TypeScript/Vite; `VITE_WS_URL=wss://deployment-check.example/ws` chỉ là endpoint giả dùng xác minh build, không phải public deployment. Logs `phase7d-frontend-build.log`. Không đưa bundle này lên production trước khi build với URL Render thật.
- Tests mới sử dụng port động, loopback sockets, timeout20s, finite request deadlines và cleanup sau từng test. WEB_ONLY vẫn start khi port TCP cấu hình bị chiếm. Hai WS peers cùng room hoàn thành production RPS/Number Guessing; health200, HELLO/PING, missing/invalidOrigin403, shutdown cả match đang chạy. Regression TCP CLI/Bot, concurrency/ordering/privacy/host migration Phase1–7 vẫn chạy.
- Hai browser thực qua public Internet chưa kiểm thử. Bằng chứng browser localhost Phase7 có trong báo cáo cũ; Phase7D dùng automated WS peers và frontend multiplayer tests.
- Docker daemon không chạy; chưa build/run image. Builder tag `maven:3.10.0-eclipse-temurin-21-noble`, runtime `eclipse-temurin:21-jre-jammy`; local Maven3.9.16/JDK21. Chưa xác minh Linux container hoặc Corepack build trên Vercel thực tế.
- Smoke test process ServerMain WEB_ONLY dùng PORT động và đúng JAVA_TOOL_OPTIONS trong Docker: health HTTP200, bind0.0.0.0, startup working set77.76 MiB,26 threads trên Windows. Logs `phase7d-startup.log`, `phase7d-startup-errors.log`, `phase7d-resource-probe.log`. Process thử nghiệm được dừng sau đo bằng Stop-Process; phép đo này không kiểm tra SIGTERM. Graceful application shutdown được kiểm tra riêng trong integration tests; chưa đo tải/CPU throttling/container RSS trên Render.

## Readiness và giới hạn

Source/config/documentation sẵn sàng để operator push và deploy theo [free-deployment.md](free-deployment.md). Chưa có URL Render/Vercel được triển khai, WSS/TLS/Origin production chưa xác minh. Free512MB/0.1CPU: limits phù hợp mục tiêu demo nhỏ, chưa đo tải thực tế trên Render; heap không đại diện tổng memory.

Render có idle spin-down/cold start, không uptime guarantee; restart/redeploy làm mất in-memory sessions/rooms/matches. Không persistence/auto reconnect/resume; browser kết nối lại thủ công. Render Web Service không expose raw TCP, CLI/Bot dùng LOCAL/LAN. Quota/free-plan và Hobby personal/noncommercial cần operator kiểm tra. Không mở rộng Phase8.
