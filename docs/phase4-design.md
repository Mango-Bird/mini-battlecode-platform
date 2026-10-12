# Chính sách Phase 4 đã được xác nhận

Ghi trước khi triển khai runtime. Giữ protocol 1.0, toàn bộ wire fields/enums và các phương thức cũ.

GameRule bổ sung default capability, minimumPlayers, activePlayers và isTurnComplete. Turn bắt đầu 0. Mỗi player chỉ submit thành công một lần trong một turn; rule đồng thời giữ turn/deadline đến khi round hoàn tất. Engine kiểm tra expectedTurn dưới cùng lock với mutation. Request-ID dedup vẫn ở adapter.

Disconnect RUNNING gọi onDisconnect; rule quyết định tiếp tục/finish. Host chuyển cho member còn lại tham gia sớm nhất. LEAVE_ROOM RUNNING bị từ chối. Không còn kết nối, lỗi rule, MAX_TURNS hoặc SERVER_SHUTDOWN thì cancel; không suy luận winners. Terminal event trước CLOSED; room bị xóa và session còn kết nối về LOBBY.

Engine dùng per-match ReentrantLock, monotonic deadline và generation chỉ đổi khi mở lượt mới. Timeout cũ không tác động lượt mới. Competition serialize lifecycle với khóa Lobby; thứ tự khóa Competition -> Engine, không chiều ngược. Callback luật là trusted synchronous code; notification sink chỉ enqueue bounded immutable events, không socket I/O dưới khóa. Shutdown ngừng nhận START/ACTION rồi cancel mọi match và đóng scheduler/transport.

PlayerSnapshot chỉ chứa status/turn/deadline/generation/view riêng, không RuleState. MATCH_EVENT lấy viewFor riêng từng session; engine giới hạn view và outcome trước publish. Luật phải stateless/reentrant, RuleState immutable, không giữ shared mutable state giữa phòng.
