# Web deployment — Phase 7 preparation

Phase 7D bổ sung WEB_ONLY và Docker cho Render Free: xem [free-deployment.md](free-deployment.md). Các ví dụ TCP + WebSocket bên dưới vẫn phục vụ LOCAL/VPS.

Frontend React/Vite nằm ngoài Maven; backend Java 21 là process lâu dài dùng chung TCP và WebSocket. Chỉ chuẩn bị cấu hình, chưa deploy Vercel/backend công khai. Domain bên dưới là placeholder operator phải thay.

## Build và local demo (Windows)

```powershell
cd D:\Socket\BattleCode
mvn clean test
mvn package
./scripts/start-web.ps1
```

Backend: TCP 127.0.0.1:9000, WS ws://127.0.0.1:8081/ws, health http://127.0.0.1:8081/health. Script có BindIp/TcpPort/WebSocketPort/Origins, Ctrl+C gọi shutdown hook. Chạy terminal khác:

```powershell
cd D:\Socket\BattleCode\frontend
Copy-Item .env.example .env.local
pnpm install --frozen-lockfile
pnpm test
$env:VITE_WS_URL='wss://<backend-domain>/ws'
pnpm build
pnpm dev --port 5173 --strictPort
```

Node 22.12+ (đã test Node 24.19.0), pnpm 11.25.0. Chọn VITE_WS_URL trong .env.local; thiếu endpoint hiển thị lỗi, không dùng localhost ngầm ở production. Hai tab http://localhost:5173: nickname → Create → Join → host Start. RPS chọn đồng thời; Number Guessing chỉ current player gửi guess. CLI/Bot dùng TCP 9000 trong cùng phòng. VITE_WS_URL public và được đưa vào bundle khi build, không đặt credentials/secrets vào VITE_*.

## LAN hai máy

Máy backend/frontend dev: chạy script -BindIp 0.0.0.0 với Origins chính xác http://<LAN-IP>:5173, rồi Vite trên 0.0.0.0:5173. .env.local đặt ws://<LAN-IP>:8081/ws và restart Vite. Hai browser mở cùng http://<LAN-IP>:5173; CLI dùng <LAN-IP>:9000. Chỉ mở các port cần thiết trong firewall theo môi trường operator. localhost trên máy khách chỉ chính máy khách, không phải máy server.

Ví dụ tham số với IP thật do operator điền:

```powershell
./scripts/start-web.ps1 -BindIp 0.0.0.0 -TcpPort 9000 -WebSocketPort 8081 -Origins 'http://<LAN-IP>:5173'
```

Đã kiểm thử nhiều session trên localhost. Chưa xác minh hai thiết bị vật lý hoặc Internet/WSS production. Giữ trạng thái LAN Phase 6 NOT VERIFIED đến khi có bằng chứng thực tế.

## Backend production

Build bằng JDK 21, mvn package; copy server JAR và toàn bộ server/target/dependency. Chạy một instance/process để giữ chung in-memory services. Không scale nhiều instance độc lập và gọi đó là một lobby chung.

```sh
export BATTLECODE_WS_PORT="${PORT:-8081}"
export BATTLECODE_WS_BIND=0.0.0.0
export BATTLECODE_ORIGINS=https://<frontend-domain>
java -cp 'server/target/server-1.0.0-SNAPSHOT.jar:server/target/dependency/*' dev.battlecode.server.ServerMain 127.0.0.1 7777 256 5000 60000 10000
```

Trên Windows classpath dùng ;. BATTLECODE_WS_PORT không set → composition TCP-only như Phase 1–6. WS_BIND mặc định bằng TCP bind; ORIGINS là danh sách exact origins phân cách dấu phẩy, không slash cuối hoặc wildcard. Bind/port/capacity/timeout TCP vẫn theo args cũ. Public health GET /health chỉ trả {"status":"ok"}, không room/session metadata; status mô tả HTTP gateway đã sẵn sàng, không SLA gameplay.

VPS/container/VM hoặc dịch vụ hỗ trợ long-running Java và WebSocket giữ backend. TLS terminate tại reverse proxy; nội bộ Java HTTP/WS. Reverse proxy truyền Upgrade/Connection/Origin, idle timeout >60s, route /ws và /health, giới hạn HTTP/body/connection phù hợp. Nếu proxy và Java cùng host có thể bind WS 127.0.0.1; nếu container host khác dùng private network. Endpoint browser là wss://<backend-domain>/ws. Không host TCP ServerSocket hoặc process Java lâu dài bằng frontend Vercel Functions.

SIGTERM/Ctrl+C → JVM hook → matches CANCELLED SERVER_SHUTDOWN, socket/session cleanup và await tasks. Chừa termination grace ≥15s. Logs stdout/stderr gồm startup, Jetty và sanitized application errors; không log raw gameplay action/private snapshot. Restart mất sessions/rooms/matches, người dùng kết nối thủ công session mới.

## Vercel frontend

1. Import repository do operator quản lý (agent không commit/push). Root Directory: frontend, framework Vite, Node 24.x.
2. frontend/vercel.json: pnpm install --frozen-lockfile; pnpm build; output dist, SPA rewrite và security headers cơ bản. packageManager pin pnpm 11.25.0; pnpm-workspace.yaml chỉ cho phép esbuild build script.
3. Cấu hình ENABLE_EXPERIMENTAL_COREPACK=1 để honor packageManager. Kiểm tra build log dùng pnpm 11.25.0 và lockfile; không chạy npm install thêm lockfile thứ hai.
4. Đặt VITE_WS_URL=wss://<backend-domain>/ws cho Production và Preview cần dùng. Build/redeploy sau đổi env.
5. Backend Origin allowlist phải chứa exact URL frontend đã được Vercel cấp. Preview origin phải thêm từng origin được phép, không wildcard *.vercel.app.
6. Xác minh health, HTTPS→WSS handshake, hai browser khác nhau, CREATE/JOIN/START/ACTION/FINISHED, privacy và disconnect. Chỉ ghi deployed sau khi có public URL và test thành công.

Tài liệu primary: [Vercel Vite](https://vercel.com/docs/frameworks/frontend/vite), [Vercel environment](https://vercel.com/docs/environment-variables), [Jetty WebSocket](https://jetty.org/docs/jetty/12.1/programming-guide/server/websocket.html). Chi tiết web schema/ordering/limits: websocket-protocol.md. Không tự reconnect/resume hoặc bổ sung Phase 8.
