var calls = 0;
function chooseMove(observation) {
    if (++calls !== 1) throw new Error("context reused");
    var move = observation.legalMoves[0];
    return {pieceId: move.pieceId, to: {row: move.to.row, col: move.to.col}};
}
