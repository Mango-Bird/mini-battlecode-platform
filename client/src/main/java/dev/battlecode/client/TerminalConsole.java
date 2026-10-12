package dev.battlecode.client;

import org.jline.reader.*;
import org.jline.terminal.*;
import java.io.IOException;

final class TerminalConsole extends CliConsole {
    private final Terminal terminal;
    private final LineReader reader;
    TerminalConsole() throws IOException {
        this(TerminalBuilder.builder().system(true).provider("jni").dumb(false).build(), null);
    }
    TerminalConsole(Terminal terminal, LineReader reader) {
        this.terminal = terminal;
        this.reader = reader == null ? LineReaderBuilder.builder().terminal(terminal).build() : reader;
    }
    String readLine(String prompt) {
        try { return reader.readLine(prompt); }
        catch (EndOfFileException | UserInterruptException ex) { return null; }
    }
    void println(String text) { reader.printAbove(text); }
    public void close() throws IOException { terminal.close(); }
}
