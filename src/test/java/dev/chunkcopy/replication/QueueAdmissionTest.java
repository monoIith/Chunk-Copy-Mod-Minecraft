package dev.chunkcopy.replication;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueueAdmissionTest {
    @Test
    void acceptsWholeJobAtBoundary() {
        assertTrue(QueueAdmission.fits(200, 56, 256));
    }

    @Test
    void rejectsWholeJobPastBoundaryAndCannotOverflow() {
        assertFalse(QueueAdmission.fits(200, 57, 256));
        assertFalse(QueueAdmission.fits(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE));
        assertFalse(QueueAdmission.fits(0, -1, 256));
    }
}
