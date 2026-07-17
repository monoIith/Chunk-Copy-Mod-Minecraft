package dev.chunkcopy.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.chunkcopy.replication.PlayerActionContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ServerPlayerInteractionManager.class)
abstract class ServerPlayerInteractionManagerMixin {
    @Shadow
    protected ServerWorld world;

    @Shadow
    @Final
    protected ServerPlayerEntity player;

    @WrapMethod(method = "tryBreakBlock")
    private boolean chunkcopy$captureBreak(BlockPos pos, Operation<Boolean> original) {
        return PlayerActionContext.run(player, world, () -> original.call(pos), Boolean.TRUE::equals);
    }

    @WrapMethod(method = "interactBlock")
    private ActionResult chunkcopy$captureBlockUse(
            ServerPlayerEntity player,
            World ignoredWorld,
            ItemStack stack,
            Hand hand,
            BlockHitResult hitResult,
            Operation<ActionResult> original
    ) {
        return PlayerActionContext.run(this.player, world,
                () -> original.call(player, ignoredWorld, stack, hand, hitResult),
                ActionResult::isAccepted);
    }

    @WrapMethod(method = "interactItem")
    private ActionResult chunkcopy$captureItemUse(
            ServerPlayerEntity player,
            World ignoredWorld,
            ItemStack stack,
            Hand hand,
            Operation<ActionResult> original
    ) {
        return PlayerActionContext.run(this.player, world,
                () -> original.call(player, ignoredWorld, stack, hand),
                ActionResult::isAccepted);
    }
}
