package dev.battlecode.client;

import org.jline.reader.*;
import org.jline.terminal.*;
import org.junit.jupiter.api.*;
import java.io.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(8)
class TerminalConsoleTest {
    @Test void asyncEventDoesNotLosePartiallyTypedCommand() throws Exception {
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        try (var input = new PipedInputStream(); var keyboard = new PipedOutputStream(input);
             var output = new ByteArrayOutputStream();
             var terminal = TerminalBuilder.builder().system(false).streams(input, output).type("xterm").build()) {
            var reader = LineReaderBuilder.builder().terminal(terminal).build();
            var console = new TerminalConsole(terminal, reader);
            var line = executor.submit(() -> console.readLine("> "));
            keyboard.write("join_room ".getBytes(java.nio.charset.StandardCharsets.UTF_8)); keyboard.flush();
            var typed = new CompletableFuture<Void>();
            scheduler.scheduleAtFixedRate(() -> {
                if (reader.getBuffer().toString().equals("join_room ")) typed.complete(null);
            }, 0, 10, TimeUnit.MILLISECONDS);
            typed.get(2, TimeUnit.SECONDS);
            console.println("ROOM_EVENT type=JOINED players=2");
            String uuid = "550e8400-e29b-41d4-a716-446655440000";
            keyboard.write((uuid + "\r").getBytes(java.nio.charset.StandardCharsets.UTF_8)); keyboard.flush();
            assertEquals("join_room " + uuid, line.get(3, TimeUnit.SECONDS));
            assertTrue(output.toString(java.nio.charset.StandardCharsets.UTF_8).contains("ROOM_EVENT"));
        } finally {
            scheduler.shutdownNow(); executor.shutdownNow();
            assertTrue(scheduler.awaitTermination(2, TimeUnit.SECONDS)); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }
}
