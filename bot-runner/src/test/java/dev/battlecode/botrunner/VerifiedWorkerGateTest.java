package dev.battlecode.botrunner;
import org.junit.jupiter.api.*;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class VerifiedWorkerGateTest {
    @Test void unavailableWorkerNeverAdmitsSource() {
        try(var worker=new VerifiedWorkerRunner(new VerifiedWorkerRunner.Config(Path.of("missing-python-runtime"),
            Path.of("missing-worker"),Path.of("missing-docker"),"sha256:"+"0".repeat(64)))) {
            assertFalse(worker.capabilities().executable());
            var request=RunnerContractTest.request(java.util.UUID.randomUUID());
            assertEquals(BotExecutionFailure.Code.RUNNER_UNAVAILABLE,worker.execute(request).toCompletableFuture().join().failure().code());
            assertTrue(worker.capabilities().controls().values().stream().allMatch("UNAVAILABLE"::equals));
        }
    }
}
