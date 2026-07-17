package dev.chunkcopy.replication;

import java.util.function.Supplier;

/** Prevents replica writes and entity clones from being captured as new source actions. */
public final class ReplicationGuard {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private ReplicationGuard() {
    }

    public static boolean isActive() {
        return DEPTH.get() > 0;
    }

    public static void run(Runnable operation) {
        call(() -> {
            operation.run();
            return null;
        });
    }

    public static <T> T call(Supplier<T> operation) {
        DEPTH.set(DEPTH.get() + 1);
        try {
            return operation.get();
        } finally {
            int depth = DEPTH.get() - 1;
            if (depth == 0) {
                DEPTH.remove();
            } else {
                DEPTH.set(depth);
            }
        }
    }
}
