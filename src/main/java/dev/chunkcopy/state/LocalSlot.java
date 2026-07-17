package dev.chunkcopy.state;

import dev.chunkcopy.replication.PositionMapping;
import dev.chunkcopy.replication.PackedChunk;
import net.minecraft.util.math.BlockPos;

/** A block coordinate normalized to a chunk-local X/Z and an absolute Y. */
public record LocalSlot(int x, int y, int z) {
    public LocalSlot {
        if (x < 0 || x > 15 || z < 0 || z > 15) {
            throw new IllegalArgumentException("chunk-local coordinates must be in [0, 15]");
        }
    }

    public static LocalSlot from(BlockPos pos) {
        return new LocalSlot(PositionMapping.local(pos.getX()), pos.getY(), PositionMapping.local(pos.getZ()));
    }

    public BlockPos inChunk(long packedChunk) {
        return new BlockPos(PackedChunk.startX(packedChunk) + x, y, PackedChunk.startZ(packedChunk) + z);
    }
}
