package dev.battlecode.network;

import java.io.*;
import java.net.*;
import java.util.concurrent.TimeUnit;

/** One instance per frame. The total deadline never resets when a byte arrives. */
final class DeadlineInputStream extends InputStream {
    private final Socket socket;
    private final InputStream input;
    private final long outerDeadline;
    private final long frameNanos;
    private long deadline;
    private boolean started;

    DeadlineInputStream(Socket socket, long outerDeadline, int frameTimeoutMillis) throws IOException {
        this.socket = socket;
        this.input = socket.getInputStream();
        this.outerDeadline = outerDeadline;
        this.deadline = outerDeadline;
        this.frameNanos = TimeUnit.MILLISECONDS.toNanos(frameTimeoutMillis);
    }

    private void beforeRead() throws IOException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new SocketTimeoutException("Read deadline exceeded");
        long millis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining) + 1);
        socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, millis));
    }

    private void afterRead(int count) throws IOException {
        long now = System.nanoTime();
        if (now >= deadline) throw new SocketTimeoutException("Read deadline exceeded");
        if (count > 0 && !started) {
            started = true;
            deadline = Math.min(outerDeadline, now + frameNanos);
        }
    }

    public int read() throws IOException {
        beforeRead(); int value = input.read(); afterRead(value == -1 ? -1 : 1); return value;
    }

    public int read(byte[] bytes, int offset, int length) throws IOException {
        if (length == 0) return 0;
        beforeRead(); int count = input.read(bytes, offset, length); afterRead(count); return count;
    }
}
