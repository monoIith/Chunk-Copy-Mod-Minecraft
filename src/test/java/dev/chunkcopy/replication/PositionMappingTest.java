package dev.chunkcopy.replication;

import dev.chunkcopy.state.LocalSlot;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PositionMappingTest {
    @Test
    void normalizesNegativeCoordinatesWithFloorMod() {
        assertEquals(15, PositionMapping.local(-1));
        assertEquals(0, PositionMapping.local(-16));
        assertEquals(1, PositionMapping.local(-15));
    }

    @Test
    void preservesAbsoluteYAndChunkLocalOffset() {
        BlockPos mapped = PositionMapping.inChunk(new BlockPos(-1, -42, 33), PackedChunk.pack(7, -4));
        assertEquals(new BlockPos(127, -42, -63), mapped);
    }

    @Test
    void localSlotValidatesAndMaps() {
        LocalSlot slot = LocalSlot.from(new BlockPos(-1, 90, 16));
        assertEquals(new LocalSlot(15, 90, 0), slot);
        assertEquals(new BlockPos(31, 90, -32), slot.inChunk(PackedChunk.pack(1, -2)));
        assertThrows(IllegalArgumentException.class, () -> new LocalSlot(16, 0, 0));
    }
}
