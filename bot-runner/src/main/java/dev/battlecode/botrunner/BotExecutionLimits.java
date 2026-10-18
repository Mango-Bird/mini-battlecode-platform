package dev.battlecode.botrunner;

/** Requested limits. Enforcement is separately reported by the actual runner. */
public record BotExecutionLimits(long wallTimeMs, long cpuTimeMs, long processMemoryBytes,
                                 long heapBytes, long stackBytes, int outputBytes) {
    public static final BotExecutionLimits LOCAL = new BotExecutionLimits(1000, 250, 128L<<20, 32L<<20, 1L<<20, 1024);
    public BotExecutionLimits {
        if (wallTimeMs < 1 || wallTimeMs > 1000 || cpuTimeMs < 1 || cpuTimeMs > 250
            || processMemoryBytes < 1 || processMemoryBytes > 128L<<20 || heapBytes < 1024 || heapBytes > 32L<<20
            || stackBytes < 1024 || stackBytes > 1L<<20 || outputBytes < 1 || outputBytes > 1024)
            throw new IllegalArgumentException("Limits outside local profile");
    }
}
