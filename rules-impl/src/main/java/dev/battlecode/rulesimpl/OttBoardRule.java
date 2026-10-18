package dev.battlecode.rulesimpl;
import dev.battlecode.rulesapi.*;
import dev.battlecode.rulesapi.bot.*;
import dev.battlecode.rulesimpl.board.*;
import java.util.*;
import java.util.stream.Collectors;
/** Trusted deterministic sequential rule; string-map transport adapter is confined to this rule. */
public final class OttBoardRule implements GameRule {
    public static final String ID=BotObservation.GAME_ID;
    private record State(List<UUID> players,BoardState board) implements RuleState {
        private State { players=List.copyOf(players); Objects.requireNonNull(board); }
        UUID identity(Side side) { return players.get(side.ordinal()); }
        Side side(UUID player) {
            int index=players.indexOf(player);
            if(index<0) throw new IllegalArgumentException("Unknown player");
            return Side.values()[index];
        }
    }
    public String id() { return ID; }
    public void validateConfig(GameConfig config) {
        Objects.requireNonNull(config);
        if(config.maxPlayers()!=2 || config.maxTurns()<200 || !config.options().isEmpty())
            throw new IllegalArgumentException("OTT requires maxPlayers=2, maxTurns>=200 and no options");
    }
    public RuleState initialState(GameConfig config,List<UUID> players) {
        validateConfig(config);
        var roster=List.copyOf(players);
        if(roster.size()!=2 || roster.get(0).equals(roster.get(1))) throw new IllegalArgumentException("Exactly two distinct players required");
        return new State(roster,BoardState.initial());
    }
    public UUID currentPlayer(RuleState raw) { State state=state(raw); return state.identity(state.board().current()); }
    /** Admissible for adjudication, not an assertion that the move itself is legal. */
    public boolean isActionAllowed(RuleState raw,UUID player,Map<String,String> action) {
        State state=state(raw);
        return !state.board().finished() && currentPlayer(raw).equals(player);
    }
    public RuleState applyAction(RuleState raw,UUID player,Map<String,String> action) {
        State state=state(raw);
        if(!isActionAllowed(raw,player,action)) throw new IllegalArgumentException("Not requested turn");
        BoardState next;
        try {
            if(action==null || !action.keySet().equals(Set.of("pieceId","to.row","to.col"))) throw new IllegalArgumentException("Invalid action keys");
            String id=action.get("pieceId");
            int row=coordinate(action.get("to.row")), col=coordinate(action.get("to.col"));
            next=state.board().submit(state.side(player),Map.of("pieceId",id,"to",Map.of("row",row,"col",col)));
        } catch(IllegalArgumentException | NullPointerException ex) {next=state.board().forfeit(state.side(player));}
        return new State(state.players(),next);
    }
    private int coordinate(String value) {
        if(value==null || !value.matches("-?(0|[1-9][0-9]*)")) throw new IllegalArgumentException("Integer coordinate required");
        return Integer.parseInt(value);
    }
    public RuleState onTurnTimeout(RuleState raw,UUID player) {
        State state=state(raw);
        return state.board().finished() || !currentPlayer(raw).equals(player)?state:new State(state.players(),state.board().forfeit(state.side(player)));
    }
    public RuleState onDisconnect(RuleState raw,UUID player) {
        State state=state(raw);
        return state.board().finished()?state:new State(state.players(),state.board().forfeit(state.side(player)));
    }
    public RuleOutcome evaluate(RuleState raw) {
        State state=state(raw); BoardState board=state.board();
        return new RuleOutcome(board.finished(),board.winner().map(side->Set.of(state.identity(side))).orElse(Set.of()),
            board.reason().map(Enum::name).orElse("OTT_RUNNING"));
    }
    /** Future runner must supply its real remaining budget; no budget is fabricated in viewFor. */
    public BotObservation observation(RuleState raw,UUID player,long remainingTimeMs) {
        State state=state(raw); return state.board().observation(state.side(player),remainingTimeMs);
    }
    public List<MoveEvent> history(RuleState raw) { return state(raw).board().history(); }
    public Map<String,String> viewFor(RuleState raw,UUID player) {
        State state=state(raw); BoardState board=state.board(); Side side=state.side(player);
        String pieces=board.board().pieces().stream().map(p->p.id()+","+p.owner()+","+p.type()+","+p.position().row()+","+p.position().col())
            .collect(Collectors.joining(";"));
        String moves=side==board.current()?board.legalMoves().stream().map(m->m.pieceId()+","+m.to().row()+","+m.to().col())
            .collect(Collectors.joining(";")):"";
        return Map.of("apiVersion",BotObservation.API_VERSION,"gameId",ID,"playerId",side.name(),
            "turn",Integer.toString(board.validMoveCount()+1),"boardSize","9","board",pieces,
            "legalMoves",moves,"validMoveCount",Integer.toString(board.validMoveCount()),
            "currentSide",board.current().name(),"yourTurn",Boolean.toString(!board.finished()&&side==board.current()));
    }
    private State state(RuleState raw) {
        if(!(raw instanceof State state)) throw new IllegalArgumentException("Not an OTT state");
        return state;
    }
}
