package dev.battlecode.client;

import java.io.*;

/** Piped input stays plain; real terminals use a line editor that can safely redraw asynchronous output. */
abstract class CliConsole implements AutoCloseable {
    static CliConsole create() throws IOException {
        return System.console() == null ? new StreamConsole() : new TerminalConsole();
    }
    abstract String readLine(String prompt) throws IOException;
    abstract void println(String text);
    public abstract void close() throws IOException;

    private static final class StreamConsole extends CliConsole {
        private final BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
        String readLine(String prompt) throws IOException { System.out.print(prompt); return input.readLine(); }
        void println(String text) { System.out.println(text); }
        public void close() { /* Do not close the process-wide System.in/out. */ }
    }
}
