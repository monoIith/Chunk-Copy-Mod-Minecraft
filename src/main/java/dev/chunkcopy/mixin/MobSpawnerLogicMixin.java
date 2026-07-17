package dev.chunkcopy.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.chunkcopy.ChunkCopyMod;
import dev.chunkcopy.replication.SpawnerCloneMarker;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.block.spawner.MobSpawnerLogic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MobSpawnerLogic.class)
abstract class MobSpawnerLogicMixin {
    @WrapOperation(
            method = "serverTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/world/ServerWorld;spawnNewEntityAndPassengers(Lnet/minecraft/entity/Entity;)Z"
            ),
            require = 1
    )
    private boolean chunkcopy$mirrorSuccessfulSpawnerMob(
            ServerWorld world,
            Entity entity,
            Operation<Boolean> original
    ) {
        boolean added = original.call(world, entity);
        if (added && entity instanceof MobEntity mob && !SpawnerCloneMarker.isMarked(entity)) {
            try {
                ChunkCopyMod.service().acceptSpawnerSpawn(world, mob);
            } catch (RuntimeException exception) {
                ChunkCopyMod.LOGGER.error("Unable to queue standard-spawner mob clones", exception);
            }
        }
        return added;
    }
}
