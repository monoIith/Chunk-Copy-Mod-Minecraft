package dev.chunkcopy.replication;

import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.TrapdoorBlock;

/** Decides whether a successful player-caused block write belongs in a replicated action. */
public final class MutationCapturePolicy {
    private MutationCapturePolicy() {
    }

    /**
     * Opening, closing, or otherwise updating an existing door, trapdoor, or fence gate is
     * local-only. Placement and removal still qualify because those writes change the block type
     * at the position.
     */
    public static boolean shouldCapture(BlockState previousState, BlockState proposedState) {
        boolean sameBlock = previousState.getBlock() == proposedState.getBlock();
        boolean localOnlyState = previousState.getBlock() instanceof DoorBlock
                || previousState.getBlock() instanceof TrapdoorBlock
                || previousState.getBlock() instanceof FenceGateBlock;
        return !(sameBlock && localOnlyState);
    }
}
