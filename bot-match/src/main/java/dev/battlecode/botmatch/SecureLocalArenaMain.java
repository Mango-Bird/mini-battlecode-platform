package dev.battlecode.botmatch;
import dev.battlecode.botrunner.VerifiedWorkerRunner;
import dev.battlecode.botmatch.persistence.Database;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
/** Independent LOCAL composition. No insecure flag or production dependency. */
public final class SecureLocalArenaMain {
    private SecureLocalArenaMain(){}
    public static void main(String[] args) throws Exception {
        dev.battlecode.botmatch.production.ProductionPreflight.requireLocalRuntime(System.getenv());
        if(args.length!=7||!args[0].equals("--local-development")||System.getenv("RENDER")!=null
            ||"WEB_ONLY".equalsIgnoreCase(System.getenv("BATTLECODE_MODE")))throw new IllegalArgumentException("Local worker configuration required");
        System.setProperty("sun.net.httpserver.maxReqTime","10");System.setProperty("sun.net.httpserver.maxRspTime","10");
        System.setProperty("jdk.httpserver.maxConnections","32");
        var config=new VerifiedWorkerRunner.Config(Path.of(args[3]),Path.of(args[4]),Path.of(args[5]),args[6]);
        var database=Database.environment();database.acquireLease();
        try{database.migrate();}catch(RuntimeException e){database.close();throw e;}
        var worker=new VerifiedWorkerRunner(config);var arena=new LocalArenaService(Path.of(args[1]),worker);
        LocalArenaHttpServer server;
        try {server=new LocalArenaHttpServer(true,Integer.parseInt(args[2]),Set.of("http://127.0.0.1:5173","http://localhost:5173"),arena,true,database);}
        catch(Exception e){arena.close();database.close();throw e;}
        Runtime.getRuntime().addShutdownHook(new Thread(server::close,"secure-arena-shutdown"));
        server.start();System.out.println("Local arena 127.0.0.1:"+server.port()+"; user bots "+(arena.userBotsExecutable()?"EXECUTABLE":"NOT_EXECUTABLE"));
        new CountDownLatch(1).await();
    }
}
