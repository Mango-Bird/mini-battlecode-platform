package dev.battlecode.botrunner;
import java.io.*;
import java.util.*;
/** Only reviewed bundled scripts can execute. Production user submissions remain disabled. */
public final class TrustedBotFixtures {
    public enum Fixture { FirstLegalMoveBot, CapturePriorityBot, InvalidActionBot, MissingFunctionBot,
        InfiniteLoopBot, RuntimeErrorBot, FreshContextBot, PromiseBot }
    private static final Map<String,String> SOURCES;
    static {
        Map<String,String> sources=new HashMap<>();
        for(Fixture fixture:Fixture.values()) {
            try(var in=TrustedBotFixtures.class.getResourceAsStream("/bots/"+fixture+".js")) {
                if(in==null) throw new IOException("Missing fixture");
                byte[] bytes=in.readAllBytes(); sources.put(BotSubmission.hash(bytes),new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
            } catch(IOException e) { throw new ExceptionInInitializerError(e); }
        }
        SOURCES=Map.copyOf(sources);
    }
    private TrustedBotFixtures() { }
    public static BotSubmission load(Fixture fixture) {
        try(var in=TrustedBotFixtures.class.getResourceAsStream("/bots/"+fixture+".js")) {
            byte[] bytes=Objects.requireNonNull(in).readAllBytes();
            return new BotSubmission(UUID.randomUUID(),1,bytes,BotSubmission.hash(bytes));
        } catch(IOException e) { throw new UncheckedIOException(e); }
    }
    static boolean allows(BotSubmission submission) {
        return submission.sourceText().equals(SOURCES.get(submission.sourceSha256()));
    }
}
