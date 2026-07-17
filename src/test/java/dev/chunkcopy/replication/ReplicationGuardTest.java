package dev.chunkcopy.replication;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplicationGuardTest {
    @Test
    void nestsAndAlwaysCleansUp() {
        assertFalse(ReplicationGuard.isActive());
        ReplicationGuard.run(() -> {
            assertTrue(ReplicationGuard.isActive());
            ReplicationGuard.run(() -> assertTrue(ReplicationGuard.isActive()));
            assertTrue(ReplicationGuard.isActive());
        });
        assertFalse(ReplicationGuard.isActive());

        assertThrows(IllegalStateException.class, () -> ReplicationGuard.run(() -> {
            throw new IllegalStateException("expected");
        }));
        assertFalse(ReplicationGuard.isActive());
    }
}
