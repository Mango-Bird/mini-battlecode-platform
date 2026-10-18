package dev.battlecode.staging;
import dev.battlecode.botmatch.persistence.Database;
import java.net.InetSocketAddress;
import java.util.concurrent.*;
public final class StagingMain {
    private StagingMain(){}
    public static void main(String[] args)throws Exception{
        if(args.length!=0)throw new IllegalArgumentException("No execution arguments supported");
        var config=new StagingConfig(System.getenv());
        System.setProperty("sun.net.httpserver.maxReqTime","15");System.setProperty("sun.net.httpserver.maxRspTime","15");System.setProperty("jdk.httpserver.maxConnections","32");
        var pool=config.pool();var db=new Database(pool);var server=new StagingHttpServer(config,db,new InetSocketAddress("0.0.0.0",config.port));
        var metrics=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("staging-diagnostics").factory());
        Runtime.getRuntime().addShutdownHook(new Thread(()->{metrics.shutdownNow();server.close();db.close();pool.close();System.out.println("{\"event\":\"stopped\"}");},"staging-shutdown"));
        server.start();metrics.scheduleAtFixedRate(server::diagnostics,30,60,TimeUnit.SECONDS);
        System.out.println("{\"event\":\"started\",\"mode\":\"SAFE_STAGING_WEB_ONLY\",\"execution\":false}");
        new CountDownLatch(1).await();
    }
}
