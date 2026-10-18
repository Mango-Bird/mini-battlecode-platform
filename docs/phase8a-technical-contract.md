# Phase 8A — Technical Contract v1.0

Clarification được người dùng xác nhận ngày 2026-10-13: FORFEIT áp dụng cho BotAction trả về ở lượt được yêu cầu (sai schema/type/extra fields/nước đi hoặc timeout). Session, membership, requestId, expectedTurn và request ngoài lượt giữ ERROR hiện có. Đây là phân biệt BotAction với lỗi request; không thay initial board, điều kiện thắng hoặc protocol. Phần Technical Contract phía dưới giữ nguyên văn.

Bản ghi nguyên văn phần Technical Contract từ yêu cầu người dùng ngày 2026-10-13. Đây là hợp đồng đã khóa; chưa sửa điều kiện thắng, bố trí, Bot API hoặc protocol.

## 2. Technical Contract Phase 8A v1.0

### 2.1. Identity

Game ID: `ott-board-9x9-v1`

Bot API version: `bot-1`

Ngôn ngữ bot: JavaScript.

Hai bot trong trận được gán side `A` và `B`, độc lập với sessionId, nickname, userId.

### 2.2. Bàn cờ

- 9 hàng × 9 cột.
- Zero-based row và col từ 0 đến 8.
- a1 = (0,0).
- i9 = (8,8).
- A đi trước.
- Luân phiên A → B.
- Mỗi bên có 6 quân: 2 ROCK, 2 PAPER, 2 SCISSORS.
- ID quân bất biến: A-R1, A-R2, A-P1, A-P2, A-S1, A-S2; tương tự bên B.

### 2.3. Initial board

Bố trí cố định:

A:
- A-R1 tại (0,0)
- A-P1 tại (0,1)
- A-S1 tại (0,2)
- A-R2 tại (1,0)
- A-P2 tại (1,1)
- A-S2 tại (1,2)

B:
- B-R1 tại (7,6)
- B-P1 tại (7,7)
- B-S1 tại (7,8)
- B-R2 tại (8,6)
- B-P2 tại (8,7)
- B-S2 tại (8,8)

A cần đến (8,8) để thắng.
B cần đến (0,0) để thắng.

Không tự đổi bố trí hoặc điều kiện thắng.

### 2.4. Movement

Một quân được đi đúng một ô theo một trong tám hướng.

Điều kiện hợp lệ:
- Quân thuộc side đang đến lượt.
- Quân tồn tại và chưa bị bắt.
- Ô đích nằm trong bàn.
- Chebyshev distance = 1.
- Ô đích trống hoặc chứa quân đối phương mà quân đang đi thắng theo RPS.
- Không đứng yên.
- Không nhảy nhiều ô.
- Không đi vào ô có quân cùng phe.
- Không bắt quân đối phương cùng loại hoặc quân khắc chế mình.

Quan hệ thắng:
- ROCK > SCISSORS
- SCISSORS > PAPER
- PAPER > ROCK

### 2.5. Win conditions

Sau khi áp dụng một nước đi hợp lệ, kiểm tra theo thứ tự:

1. GOAL_REACHED:
   - A đến i9.
   - B đến a1.

2. PIECE_TYPE_ELIMINATED:
   - Bot loại hết cả hai quân thuộc một loại của đối phương.

3. NO_LEGAL_MOVES:
   - Khi bắt đầu lượt kế tiếp, nếu bot đó không có nước đi hợp lệ thì bot đó thua.

4. MOVE_LIMIT_REACHED:
   - Nếu đã áp dụng 200 nước đi hợp lệ và chưa có bên thắng, trận hòa.

Nước đi không hợp lệ hoặc bot không trả hành động đúng hạn: FORFEIT ngay, không retry.

Chỉ tăng validMoveCount sau khi action hợp lệ được áp dụng thành công.

### 2.6. BotObservation

Schema logic:

{
  "apiVersion": "bot-1",
  "gameId": "ott-board-9x9-v1",
  "playerId": "A",
  "turn": 1,
  "boardSize": 9,
  "board": [
    {
      "id": "A-R1",
      "owner": "A",
      "type": "ROCK",
      "row": 0,
      "col": 0
    }
  ],
  "legalMoves": [
    {
      "pieceId": "A-R1",
      "to": {"row": 1, "col": 0}
    }
  ],
  "remainingTimeMs": 1000
}

Yêu cầu:
- board chứa toàn bộ quân còn sống.
- board sắp xếp theo ID tăng dần.
- legalMoves sắp xếp theo pieceId, to.row, to.col.
- turn bắt đầu từ 1.
- Không lộ session token hoặc server internals.
- Observation immutable.
- Không để bot sửa trực tiếp board state.

`remainingTimeMs` do runner Phase 8C cung cấp. Phase 8B chỉ định nghĩa contract/DTO, không tự giả lập execution budget.

### 2.7. BotAction

{
  "pieceId": "A-R1",
  "to": {
    "row": 1,
    "col": 0
  }
}

Yêu cầu:
- Chỉ hai trường top-level: pieceId và to.
- to chỉ có row và col.
- row/col phải là integer.
- Không chấp nhận null, extra fields, sai type hoặc action không thuộc legalMoves.
- Invalid action → FORFEIT.
- Không retry.

### 2.8. JavaScript Bot Contract

Hàm dự kiến:

chooseMove(observation)

- Hàm đồng bộ.
- Trả về BotAction.
- Không dùng Promise.
- Không dùng network/filesystem/subprocess.
- Không thư viện ngoài.
- Không giữ state qua lượt.
- Không triển khai execution trong Phase 8B.
