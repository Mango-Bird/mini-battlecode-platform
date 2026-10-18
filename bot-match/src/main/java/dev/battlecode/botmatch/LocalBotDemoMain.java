package dev.battlecode.botmatch;

import dev.battlecode.botrunner.*;
import dev.battlecode.competition.*;
import dev.battlecode.engine.*;
import dev.battlecode.rulesapi.*;
import dev.battlecode.rulesimpl.OttBoardRule;
import dev.battlecode.rulesimpl.board.Board;
import dev.battlecode.rulesapi.bot.BotPiece;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Opt-in offline local composition. Never attached to ServerMain or Render WEB_ONLY. */
public final class LocalBotDemoMain {
    private LocalBotDemoMain() { }
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Usage: LocalBotDemoMain <pinned-quickjs-executable>");
        var rule=new OttBoardRule();var registry=new InMemoryRuleRegistry(List.of(rule));
        var lobby=new InMemoryLobby(registry);var engine=new DefaultGameEngine();
        var manager=new DefaultMatchManager(lobby,engine,registry,n->{});
        var raw=new TrustedQuickJsRunner(Path.of(args[0]));
        try(var service=new BotExecutionService(raw);var coordinator=new BotMatchCoordinator(lobby,manager,rule,service,TrustedQuickJsRunner.MODE)) {
            var a=TrustedBotFixtures.load(TrustedBotFixtures.Fixture.FirstLegalMoveBot);
            var b=TrustedBotFixtures.load(TrustedBotFixtures.Fixture.CapturePriorityBot);
            var summary=coordinator.start(a,b).completion().toCompletableFuture().get(30,TimeUnit.SECONDS);
            var replay=Board.replay(summary.history());
            var replayPieces=replay.pieces().stream().map(p->new BotPiece(p.id(),p.owner().name(),p.type().name(),p.position().row(),p.position().col()))
                .sorted(Comparator.comparing(BotPiece::id)).toList();
            boolean replayMatches=summary.finalObservation()!=null && replayPieces.equals(summary.finalObservation().board());
            System.out.println("Mode="+TrustedQuickJsRunner.MODE+"; JavaScript=QuickJS external process");
            System.out.println("Match="+summary.matchId()+"; result="+summary.result().reason()+"; winners="+summary.result().winners().size());
            System.out.println("Validated moves="+summary.history().size()+"; replayMatches="+replayMatches);
            System.out.println("Source A SHA256="+summary.sourceHashA()+"; Source B SHA256="+summary.sourceHashB());
            System.out.println("Capabilities="+raw.capabilities()+"; liveProcesses="+raw.liveProcesses());
            if(!replayMatches || summary.history().isEmpty() || !Set.of("GOAL_REACHED","PIECE_TYPE_ELIMINATED","NO_LEGAL_MOVES","MOVE_LIMIT_REACHED").contains(summary.result().reason()))
                throw new IllegalStateException("Demo did not complete a validated gameplay match");
        } finally {manager.shutdown();if(!manager.awaitTermination(2,TimeUnit.SECONDS))throw new IllegalStateException("Manager did not stop");}
    }
}
