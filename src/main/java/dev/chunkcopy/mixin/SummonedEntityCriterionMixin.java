package dev.chunkcopy.mixin;

import dev.chunkcopy.replication.ReplicationGuard;
import net.minecraft.advancement.criterion.SummonedEntityCriterion;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A golem produced by a copied pumpkin is local gameplay, not another player's advancement. */
@Mixin(SummonedEntityCriterion.class)
abstract class SummonedEntityCriterionMixin {
    @Inject(
            method = "trigger(Lnet/minecraft/server/network/ServerPlayerEntity;Lnet/minecraft/entity/Entity;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void chunkcopy$suppressReplicaAdvancement(
            ServerPlayerEntity player,
            Entity entity,
            CallbackInfo callback
    ) {
        if (ReplicationGuard.isActive()) {
            callback.cancel();
        }
    }
}
