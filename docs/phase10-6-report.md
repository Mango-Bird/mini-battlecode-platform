# Phase 10.6 — Product UI Simplification

## Phạm vi hoàn thành

Tinh giản frontend trên design system Phase 10.5, không bổ sung framework hoặc dependency. Không sửa Java backend, PostgreSQL, authentication contract, worker security, OTT rules, API endpoints hoặc deployment trong phase này. Workspace có thay đổi từ các phase trước; không commit, push hoặc deploy.

- Account: username và Logout là nội dung chính. User ID nằm trong Account details mặc định đóng; bỏ mô tả cookie/database.
- My Bots: New Bot, tên bot, version, trạng thái dễ hiểu và Edit. ID, SHA-256 và validation metadata nằm trong Details mặc định đóng.
- Bot Editor: giữ cảnh báo chưa lưu, lỗi, kết quả validation và eligibility; metadata/API reference nằm trong disclosure. Không thay đổi save/version/hash hoặc logic thực thi.
- Arena: chọn hai bot, Start Battle và bàn cờ là trọng tâm. Bỏ IDLE badge; replay chỉ xuất hiện khi có nước đi, capture panel khi có capture, log khi có moves. Kết quả vẫn hiển thị; lý do bổ sung trong Result details. Previous battles là disclosure.
- Tournaments: primary action Create Tournament mở form; seed/rematch chuyển vào Advanced settings. Listing hiển thị format, trạng thái dễ hiểu, số người tham gia thực tế và View/Join. Giữ nguyên owner permissions, eligibility và version/hash trong request.
- Global: bỏ eyebrow lặp, giảm header/padding, đơn giản sidebar/footer. Breadcrumb chỉ còn ở Bot Editor và Tournament Detail. Các trang giữ một H1; feedback lỗi và focus/keyboard behavior vẫn giữ.

## File thêm/sửa trong phase này

Presentation: `frontend/src/product-labels.ts` (mới), `ui.tsx`, `layouts.tsx`, `pages.tsx`, `platform.css`, `Auth.tsx`, `Platform.tsx`, `BotWorkspace.tsx`, `BotArena.tsx`, `Tournaments.tsx`.

Tests: `frontend/src/product-ui.test.tsx` (mới), `Platform.test.tsx`, `BotWorkspace.test.tsx`, `BotArena.test.tsx`, `Tournaments.test.tsx`.

Tài liệu/ảnh: báo cáo này và `docs/screenshots/phase10-6/`.

`productLabel` chỉ chuyển enum thành nhãn hiển thị; không đổi model hoặc wire values. Details giữ dữ liệu kỹ thuật, không xóa dữ liệu bên dưới. Không thêm fake statistics hoặc tournaments.

## Xác minh

| Kiểm tra | Kết quả |
| --- | --- |
| `pnpm test` | PASS: 12 files, 50 tests, không skip |
| Auth/session và routing | Regression tests PASS |
| Workspace save/version | Regression tests PASS |
| Arena match/replay | Regression tests PASS |
| Tournament owner permissions | Regression tests PASS |
| Product UI disclosures/labels/idle state | 4 tests PASS |
| `pnpm build` | PASS: TypeScript + Vite production build |

Build sử dụng `VITE_WS_URL=wss://build-verification.invalid/ws` tạm thời để kiểm tra production guard hiện có; biến được khôi phục sau build. Đây là URL kiểm tra, **không dùng artifact này để deploy**; build deployment cần WSS thật. Không sửa `.env`, packageManager hoặc lockfile.

Browser tooling đã kiểm tra Home desktop 1440×1000, Home/Tournaments mobile 390×844, và Account auth guard 320×740. Các trang đã kiểm tra có một H1, không tràn ngang; menu mobile đóng khi chuyển route. Screenshots: `home-desktop.png`, `auth-mobile.png` (capture Home mobile không thành công; kiểm tra DOM vẫn thực hiện được).

Docker daemon không hoạt động nên không khởi động được PostgreSQL/backend test. Kiểm tra browser lần này giới hạn ở public/signed-out UI; Account hiển thị lỗi kết nối và không lộ nội dung cần đăng nhập. Không tuyên bố đã chạy browser end-to-end cho Account/My Bots/Arena/Tournaments có session thật; các hành vi này được kiểm tra bằng automated frontend tests với API mocks. Không chạy Java tests vì không sửa backend.

## Chạy frontend

Trong môi trường đã có Node và pnpm của project:

```powershell
Set-Location D:\Socket\BattleCode\frontend
pnpm test
pnpm build # cần VITE_WS_URL production hợp lệ theo cấu hình hiện có
pnpm exec vite --host 127.0.0.1 --port 5173 --strictPort
```

Backend và cấu hình chạy local tiếp tục dùng hướng dẫn Phase 10/10.5 hiện có. Phase 10.6 kết thúc ở frontend simplification; không chuyển phase tiếp theo.
