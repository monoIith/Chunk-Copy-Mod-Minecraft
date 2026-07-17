package dev.chunkcopy.mixin;

import dev.chunkcopy.replication.ReplicationGuard;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Replica consequences may create gameplay entities, but never reward item drops or XP. */
@Mixin(ServerWorld.class)
abstract class ServerWorldMixin {
    @Inject(
            method = "spawnEntity(Lnet/minecraft/entity/Entity;)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private void chunkcopy$suppressReplicaRewards(Entity entity, CallbackInfoReturnable<Boolean> callback) {
        if (ReplicationGuard.isActive()
                && (entity instanceof ItemEntity || entity instanceof ExperienceOrbEntity)) {
            callback.setReturnValue(false);
        }
    }
}
