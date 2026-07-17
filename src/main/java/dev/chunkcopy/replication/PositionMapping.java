package dev.chunkcopy.replication;

import net.minecraft.util.math.BlockPos;

/** Coordinate conversion shared by live replication, persistence, and tests. */
public final class PositionMapping {
    private PositionMapping() {
    }

    public static int local(int blockCoordinate) {
        return Math.floorMod(blockCoordinate, 16);
    }

    public static BlockPos inChunk(BlockPos source, long targetChunk) {
        return new BlockPos(
                PackedChunk.startX(targetChunk) + local(source.getX()),
                source.getY(),
                PackedChunk.startZ(targetChunk) + local(source.getZ())
        );
    }
}
