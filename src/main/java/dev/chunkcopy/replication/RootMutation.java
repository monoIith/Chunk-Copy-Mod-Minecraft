package dev.chunkcopy.replication;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

/** A successful depth-zero block write made by a player interaction. */
public record RootMutation(BlockPos sourcePos, BlockState state, int flags, int maxUpdateDepth) {
    public RootMutation {
        sourcePos = sourcePos.toImmutable();
    }
}
