package dev.chunkcopy.mixin;

import dev.chunkcopy.replication.ReplicationGuard;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stops replica XP before vanilla can merge it into an existing orb. */
@Mixin(ExperienceOrbEntity.class)
abstract class ExperienceOrbEntityMixin {
    @Inject(
            method = "spawn(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Vec3d;I)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void chunkcopy$suppressReplicaXp(
            ServerWorld world,
            Vec3d position,
            Vec3d velocity,
            int amount,
            CallbackInfo callback
    ) {
        if (ReplicationGuard.isActive()) {
            callback.cancel();
        }
    }
}
