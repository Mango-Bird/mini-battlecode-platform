package dev.battlecode.rulesapi.bot;
import java.util.*;
/** No identity tokens. remainingTimeMs is supplied by a future runner, never invented by the rule. */
public record BotObservation(String apiVersion, String gameId, String playerId, int turn, int boardSize,
                             List<BotPiece> board, List<BotMove> legalMoves, long remainingTimeMs) {
    public static final String API_VERSION = "bot-1";
    public static final String GAME_ID = "ott-board-9x9-v1";
    public BotObservation {
        if (!API_VERSION.equals(apiVersion) || !GAME_ID.equals(gameId) || playerId == null || !Set.of("A","B").contains(playerId)
            || turn < 1 || boardSize != 9 || remainingTimeMs < 0) throw new IllegalArgumentException("Invalid observation");
        board = board.stream().sorted(Comparator.comparing(BotPiece::id)).toList();
        legalMoves = legalMoves.stream().sorted(Comparator.comparing(BotMove::pieceId)
            .thenComparingInt(m -> m.to().row()).thenComparingInt(m -> m.to().col())).toList();
    }
}
