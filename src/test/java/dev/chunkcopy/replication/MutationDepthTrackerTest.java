package dev.chunkcopy.replication;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutationDepthTrackerTest {
    @Test
    void onlyDepthZeroEntryIsRoot() {
        MutationDepthTracker tracker = new MutationDepthTracker();
        assertTrue(tracker.enter());
        assertFalse(tracker.enter());
        assertEquals(2, tracker.depth());
        tracker.leave();
        tracker.leave();
        assertEquals(0, tracker.depth());
        assertTrue(tracker.enter());
    }

    @Test
    void rejectsDepthUnderflow() {
        assertThrows(IllegalStateException.class, new MutationDepthTracker()::leave);
    }
}
