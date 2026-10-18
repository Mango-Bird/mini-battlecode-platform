function chooseMove(observation) {
    var move = observation.legalMoves[0];
    return {pieceId: move.pieceId, to: {row: move.to.row, col: move.to.col}};
}
