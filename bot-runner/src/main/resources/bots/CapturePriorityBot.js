function chooseMove(observation) {
    var moves = observation.legalMoves;
    for (var i = 0; i < moves.length; i++) {
        var move = moves[i];
        for (var j = 0; j < observation.board.length; j++) {
            var piece = observation.board[j];
            if (piece.owner !== observation.playerId && piece.row === move.to.row && piece.col === move.to.col)
                return {pieceId: move.pieceId, to: {row: move.to.row, col: move.to.col}};
        }
    }
    var first = moves[0];
    return {pieceId: first.pieceId, to: {row: first.to.row, col: first.to.col}};
}
