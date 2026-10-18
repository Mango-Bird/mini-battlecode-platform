# Phase 8C.1 — Design report

Ngày2026-10-13, workspace D:\Socket\BattleCode. Hoàn thành thiết kế và technical proposal; chưa triển khai executor/sandbox/coordinator hoặc Phase8C.2.

## Deliverables

1. [Runner design](phase8c-runner-design.md): source-mapped contracts, architecture, state machine, proposed7 abstractions, bridge gaps, fairness/replay.
2. [Execution protocol](phase8c-execution-protocol.md): runner-1 request/result schema, framing/HTTPS, sequence, deadlines/correlation/cancellation/duplicate/restart/capacity/payload bounds và error matrix.
3. [Security](phase8c-security.md): threat model, A/B/C comparison, Windows/native and production/Wasm recommendations, source/JS/OS controls, Render constraints.
4. [Implementation plan](phase8c-implementation-plan.md): approval gates và phases8C.2–8C.5 acceptance criteria.
5. Báo cáo này.

Tất cả interface names/signatures/resource numbers/new endpoints/module names là proposed, không existing API hoặc measured guarantees.

## Findings from current source

BotObservation và BotAction đã immutable/strict ở DTO; observation includes caller-provided budget, no session metadata. OttBoardRule.observation cần RuleState/UUID/budget, GameEngine.snapshot không cung cấp RuleState. MatchManager.startMatch chỉ UUID; không có typed snapshot bridge/pause/cancel-one-match operation trong interface. DefaultMatchManager tick gọi timeout mọi match, engine expired turn gọi OTT FORFEIT. Vì vậy không thể chỉ gắn async Runner vào STARTED event rồi đảm bảo infrastructure failure không xử thua: cần cause-aware BOT integration và state lease trước8C.4. Existing committed GameState refs có thể được retained dưới lifecycle lock, không mirror board/second engine.

Cả malformed Runner control frames, HTTP timeout, queue/cold start/unknown crash đều là infrastructure; bot fault cần evidence từ trusted supervisor hoặc strict action validation. Cancel policy được đề xuất vì MatchStatus không có PAUSED và deadline suspension chưa tồn tại. Manual session/request/expectedTurn/out-of-turn guards vẫn giữ nguyên.

## Recommendations pending approval

- Local: separate QuickJS process, AppContainer+Job Objects helper, no arbitrary code until isolation probes pass.
- Production: trusted QuickJS-Wasm inside external Wasmtime process + OS isolation trên Linux worker riêng. Không mặc định Docker an toàn và không dùng Node vm/eval trong backend.
- Render Free giữ backend/WS demo; hostile execution disabled there. External worker cost/capabilities cần operator chọn, không bảo đảm miễn phí.
- Immutable source hash/version/runtime profile per match; fresh execution each turn, own source only. Board replay uses validated events, no automatic rerun after restart.
- Candidate resource/admission budgets và JS subset/randomness policy cần approve/calibrate; chưa benchmark.
- New internal manager bridge/cause-aware watchdog/server-owned bot seats cần approve trước coordinator implementation. Không tự thêm public interface methods hoặc external submission API.

## Verification và phạm vi

Đã đọc/đối chiếu rules-api.bot/GameRule, OTT board/rule/history, GameEngine/DefaultGameEngine/PlayerSnapshot/MatchStatus, MatchManager/DefaultMatchManager, server composition/dispatcher và network contract; cùng locked8A/8B design/report. Đã tham khảo primary documentation của Microsoft, QuickJS, Wasmtime, Docker/gVisor, Node và Render; links nằm gần claims trong security/protocol design. Không thử nghiệm sandbox hoặc claim chống escape.

Repo có sẵn các thay đổi Phase8B chưa commit khi bắt đầu. Chỉ thêm năm files docs/phase8c*.md, giữ nguyên các thay đổi cũ. SHA-256 trước/sau của144 files Java/frontend/poms/deployment/scripts trong tập kiểm tra trùng khớp; không sửa Java source/frontend/dependencies/deployment. Năm tài liệu có Markdown fences cân bằng, local links tồn tại và không trailing whitespace. Đã rà soát state names/protocol consistency, gồm epoch+admission expiry để không dựa vào duplicate cache vô hạn; không claim Mermaid renderer/browser verification. Không chạy Maven tests hoặc frontend build vì không thay code;244 tests PASS là baseline đã ghi trong báo cáo8B, không phải run mới của8C.1.

Không commit/push/deploy, không bắt đầu8C.2. User cần phê duyệt các gate trong implementation-plan.md trước công việc tiếp theo; đặc biệt isolation host, limits và BOT deadline/identity bridge.
