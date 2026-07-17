package dev.chunkcopy.replication;

/** Overflow-safe whole-job admission check shared by bounded queues. */
public final class QueueAdmission {
    private QueueAdmission() {
    }

    public static boolean fits(long queued, long requested, long limit) {
        return queued >= 0
                && requested >= 0
                && limit >= 0
                && requested <= limit
                && queued <= limit - requested;
    }
}
