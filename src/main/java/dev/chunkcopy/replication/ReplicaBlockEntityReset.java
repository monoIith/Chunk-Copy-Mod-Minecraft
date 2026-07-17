package dev.chunkcopy.replication;

import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/** Replaces all serialized and runtime-only destination data with a fresh blank block entity. */
final class ReplicaBlockEntityReset {
    private ReplicaBlockEntityReset() {
    }

    static void reset(ServerWorld world, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof BlockEntityProvider provider)) {
            return;
        }

        BlockEntity blank = provider.createBlockEntity(pos, state);
        if (blank == null) {
            return;
        }

        world.removeBlockEntity(pos);
        world.addBlockEntity(blank);
    }
}
