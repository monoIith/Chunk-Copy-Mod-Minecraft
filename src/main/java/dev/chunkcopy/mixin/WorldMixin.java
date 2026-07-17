package dev.chunkcopy.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.chunkcopy.replication.PlayerActionContext;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(World.class)
abstract class WorldMixin {
    @WrapMethod(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z")
    private boolean chunkcopy$captureBlockWrite(
            BlockPos pos,
            BlockState state,
            int flags,
            int maxUpdateDepth,
            Operation<Boolean> original
    ) {
        if (!((Object) this instanceof ServerWorld serverWorld)) {
            return original.call(pos, state, flags, maxUpdateDepth);
        }

        PlayerActionContext.MutationToken token = PlayerActionContext.enterMutation(
                serverWorld,
                pos,
                state,
                flags,
                maxUpdateDepth
        );
        try (token) {
            boolean success = original.call(pos, state, flags, maxUpdateDepth);
            token.complete(success);
            return success;
        }
    }
}
