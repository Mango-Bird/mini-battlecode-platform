package dev.battlecode.staging;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
@Timeout(20)
class WorkerAbsenceTest {
    public static void main(String[] args){
        int refused=0;
        try{new dev.battlecode.botrunner.VerifiedWorkerRunner(null);}catch(IllegalStateException expected){refused++;}
        try{new dev.battlecode.botrunner.TrustedQuickJsRunner(null);}catch(IllegalStateException expected){refused++;}
        try{new dev.battlecode.botrunner.BotExecutionService(null);}catch(IllegalStateException expected){refused++;}
        if(refused!=3)throw new AssertionError("Worker initialized");
    }
    @Test void stagingProcessCannotInitializeAnyRealRunner()throws Exception{
        var pb=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),WorkerAbsenceTest.class.getName());
        pb.environment().put("BATTLECODE_ENV","staging");pb.environment().put("BATTLECODE_MODE",StagingConfig.MODE);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        var p=pb.start();try{assertTrue(p.waitFor(10,TimeUnit.SECONDS));assertEquals(0,p.exitValue());}finally{p.destroyForcibly();p.waitFor(5,TimeUnit.SECONDS);}
    }
}
