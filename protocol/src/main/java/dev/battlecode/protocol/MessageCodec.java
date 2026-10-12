package dev.battlecode.protocol;
import java.io.*;
import java.util.Optional;
public interface MessageCodec {
    Optional<Message> read(InputStream input) throws IOException;
    void write(OutputStream output, Message message) throws IOException;
}
