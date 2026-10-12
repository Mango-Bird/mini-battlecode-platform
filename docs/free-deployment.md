# Free deployment — Phase 7D

Chuẩn bị cho một backend Render và frontend Vercel; agent chưa push hoặc deploy. Domain trong dấu `<...>` phải thay bằng URL thực tế. Hobby dành cho demo cá nhân, phi thương mại: [Vercel Hobby](https://vercel.com/docs/plans/hobby).

## A. Đưa source lên GitHub

Operator tạo repository GitHub. Nếu thư mục chưa là Git repository, chạy `git init -b main`. Kiểm tra `.gitignore`, không đưa `.env.local`, credentials, caches, target, node_modules hoặc logs lên Git. Sau đó tự thực hiện:

```powershell
git add .
git diff --cached --stat
git commit -m "Prepare free web deployment"
git remote add origin https://github.com/<owner>/<repository>.git
git push -u origin main
```

Nếu đã có repository/remote/branch, dùng cấu hình hiện có thay vì chạy lại init hoặc thêm remote. Agent không thực hiện các lệnh này.

## B. Render backend

1. Dashboard → New → Web Service → Connect GitHub → chọn repository/branch.
2. Runtime **Docker**, context repository root, Dockerfile `./Dockerfile`, instance **Free**, một instance. Không đặt Build/Start command thay thế entrypoint Docker, không cần database.
3. Health Check Path: `/health`. Đặt environment:

| Variable | Giá trị |
|---|---|
| `BATTLECODE_MODE` | `WEB_ONLY` (Docker cũng mặc định giá trị này) |
| `PORT` | Render cấp; không cần đặt thủ công |
| `BATTLECODE_ORIGINS` | Exact frontend origin, phân cách bằng dấu phẩy; không path, slash cuối hoặc wildcard |
| `JAVA_TOOL_OPTIONS` | Docker mặc định heap/CPU flags bên dưới; không cần override |

Khi chưa có URL Vercel, dùng `BATTLECODE_ORIGINS=https://bootstrap.invalid` để khởi động và kiểm tra health; origin tạm này không cho browser thật truy cập. Thay ngay theo bước D.

4. Deploy. Docker builder Maven/JDK 21 chạy tests và package. Runtime JRE 21 chỉ chứa server JAR và dependency JAR, chạy user không phải root. Không đưa frontend, local environment hoặc secrets vào image.
5. Đọc logs: `Server mode: WEB_ONLY`, gateway `0.0.0.0:<PORT>`. Mở `https://<assigned-service>.onrender.com/health`, phải trả HTTP 200 với `{"status":"ok"}`.
6. Public endpoint browser: `wss://<assigned-service>.onrender.com/ws`. Không thêm nội bộ `PORT` vào URL công khai. TLS do Render xử lý.

WEB_ONLY không bind TCP và không cần TCP port trống. `BATTLECODE_WS_PORT` và `BATTLECODE_WS_BIND` dành cho LOCAL, không điều khiển WEB_ONLY. Vẫn dùng đúng một Lobby, MatchManager, GameEngine và registry. [Render port binding](https://render.com/docs/web-services), [Docker](https://render.com/docs/docker), [WebSocket](https://render.com/docs/websocket).

Free có 512 MB RAM, 0.1 CPU: [compute plans](https://render.com/docs/compute-plans). WEB_ONLY mặc định tối đa 16 connections, outbound queue 32 mỗi connection, write timeout 10 giây. Jetty tối đa 32 platform threads; không executor không giới hạn mới. Docker JVM:

```text
-Xms32m -Xmx192m -XX:MaxMetaspaceSize=96m -XX:MaxDirectMemorySize=64m
-XX:ReservedCodeCacheSize=48m -Xss512k -XX:+UseSerialGC
-XX:ActiveProcessorCount=1 -XX:+ExitOnOutOfMemoryError
```

Các giới hạn này chừa chỗ cho native memory; heap limit không phải tổng RSS. Smoke test local entrypoint với đúng flags: health200, startup working set77.76 MiB trên Windows. Đây là cấu hình demo nhỏ, chưa load-test trên CPU/RAM Render thực tế. Không tăng connection limit tùy tiện. SIGTERM đi trực tiếp tới JVM → shutdown hook → cancellation/cleanup hiện có; Render cho termination window 30 giây, application sử dụng thời gian chờ hữu hạn.

## C. Vercel frontend

1. Add New Project → Import GitHub repository.
2. Root Directory **frontend**, Framework **Vite**, Node **24.x**.
3. Install command `pnpm install --frozen-lockfile`, Build command `pnpm build`, Output Directory `dist`. `frontend/vercel.json` đã chứa cấu hình và SPA rewrite tới `/index.html`.
4. Đặt `ENABLE_EXPERIMENTAL_COREPACK=1`; packageManager pin `pnpm@11.25.0`, lockfile đã có. Kiểm tra build log đúng phiên bản thay vì dùng pnpm mặc định khác. [Vercel build configuration](https://vercel.com/docs/builds/configure-a-build), [package managers](https://vercel.com/docs/package-managers).
5. Đặt Production `VITE_WS_URL=wss://<assigned-service>.onrender.com/ws`. Đây là dữ liệu public trong bundle, không phải secret. Production build từ chối thiếu URL, `ws://`, credentials và localhost/loopback.
6. Deploy và ghi lại exact production origin `https://<project>.vercel.app` hoặc custom domain thực tế. Đổi VITE_WS_URL cần rebuild/redeploy frontend.

`.env.example` chỉ phục vụ local development, không copy làm production config. Preview URL chỉ sử dụng khi được allowlist riêng; không dùng wildcard toàn bộ vercel.app. Corepack/provider build và public TLS chưa được xác minh bằng deployment thực tế.

## D. Hoàn thiện Origin allowlist

Trên Render thay origin bootstrap bằng exact Vercel production origin, ví dụ `https://<project>.vercel.app`. Nếu có hai domain chính thức, phân cách dấu phẩy. Save/redeploy hoặc restart để process đọc environment mới. Health vẫn public; WebSocket thiếu/sai Origin bị HTTP 403. Không xác định quyền host bằng nickname; browser so sánh server-issued playerId và hostId.

## E. Test hai browser trên Internet

Mở health và đợi backend thức trước; sau đó mở Vercel trên hai browser/profile khác nhau. Nhập nickname khác nhau, A tạo phòng, B nhập roomId để join, A Start.

- RPS: cả hai chọn; lựa chọn đối phương chưa công bố không được lộ trước khi đủ action; kiểm tra kết quả và room cleanup.
- Number Guessing: chỉ current player được guess; kiểm tra LOWER/HIGHER và kết thúc, không thấy secret trước kết quả.
- Kiểm tra host migration khi host rời phòng đang chờ, lỗi Origin, disconnect; không có tự reconnect/resume. Restart backend làm mất session, người dùng kết nối và tạo phòng mới thủ công.

Ghi URL và kết quả thực tế trước khi tuyên bố deployed. Integration tests dùng hai WebSocket peers mô phỏng browser; chưa thay thế việc test hai browser qua public Internet.

## Local verification và hạn chế

```powershell
cd D:\Socket\BattleCode
mvn clean test
mvn package
$env:BATTLECODE_MODE='WEB_ONLY'
$env:PORT='10000'
$env:BATTLECODE_ORIGINS='http://localhost:5173'
java -cp 'server/target/server-1.0.0-SNAPSHOT.jar;server/target/dependency/*' dev.battlecode.server.ServerMain
```

Ctrl+C để graceful shutdown. Local TCP + WS giữ nguyên: `./scripts/start-web.ps1` đặt mode LOCAL. Chạy frontend dev với `.env.local` URL `ws://127.0.0.1:10000/ws`; production build phải đặt URL WSS thật. Nếu Docker daemon có sẵn, operator có thể chạy `docker build -t mini-battlecode .` và `docker run --rm --memory=512m --cpus=0.1 -p 10000:10000 -e PORT=10000 -e BATTLECODE_ORIGINS=http://localhost:5173 mini-battlecode`. Không yêu cầu Docker Desktop để chạy Maven tests.

Render Free spin-down sau 15 phút không có inbound HTTP/WS traffic; cold start có thể khoảng một phút. Browser kết nối quá sớm có thể timeout: đợi health rồi kết nối thủ công. State in-memory mất khi restart/redeploy/spin-down; không persistence hoặc SLA uptime. Free có quota và có thể bị suspend; xem dashboard trước demo. Chỉ expose HTTP/WebSocket, không public raw TCP: CLI/Bot vẫn dùng local/LAN ở LOCAL mode. [Render Free limitations](https://render.com/docs/free).
