package dev.chunkcopy.replication;

import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;

/** Decides whether a successful player-caused block write belongs in a replicated action. */
public final class MutationCapturePolicy {
    private MutationCapturePolicy() {
    }

    /**
     * Opening, closing, or otherwise updating an existing door is local-only. Door placement and
     * removal still qualify because those writes change the block type at the position.
     */
    public static boolean shouldCapture(BlockState previousState, BlockState proposedState) {
        return !(previousState.getBlock() instanceof DoorBlock
                && previousState.getBlock() == proposedState.getBlock());
    }
}
