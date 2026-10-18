package dev.battlecode.botmatch;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;
/** Local-bots profile entry point; deliberately absent from production server/deployment. */
public final class LocalArenaMain {
    private LocalArenaMain() {}
    public static void main(String[] args) throws Exception {
        if(args.length<2||args.length>3||!args[0].equals("--local-development")
            ||System.getenv("RENDER")!=null||"WEB_ONLY".equalsIgnoreCase(System.getenv("BATTLECODE_MODE")))
            throw new IllegalArgumentException("Local development only: LocalArenaMain --local-development <qjs.exe> [port]");
        int port=args.length==3?Integer.parseInt(args[2]):8090;
        System.setProperty("sun.net.httpserver.maxReqTime","10");
        System.setProperty("sun.net.httpserver.maxRspTime","10");
        System.setProperty("jdk.httpserver.maxConnections","32");
        var service=new LocalArenaService(Path.of(args[1]));
        LocalArenaHttpServer http;
        try {http=new LocalArenaHttpServer(true,port,Set.of("http://127.0.0.1:5173","http://localhost:5173"),service);}
        catch(Exception e){service.close();throw e;}
        Runtime.getRuntime().addShutdownHook(new Thread(http::close,"local-arena-shutdown"));
        http.start();System.out.println("Local Bot Arena: http://127.0.0.1:"+http.port()+" (trusted QuickJS fixtures only)");
        new CountDownLatch(1).await();
    }
}
