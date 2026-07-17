package dev.chunkcopy.replication;

import dev.chunkcopy.ChunkCopyMod;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Sanitized serialized state and chunk-local position for one successful standard-spawner mob. */
public record SpawnerEntitySnapshot(
        NbtCompound entityData,
        double localX,
        double y,
        double localZ,
        float yaw,
        float pitch,
        long sourceChunk
) {
    /*
     * This is intentionally an allowlist. Mob NBT contains more references than the obvious UUID fields:
     * brains, gossip, anger listeners, homes, patrol targets, hives, and mod-defined data can all point
     * back into the source chunk. Unknown data is therefore discarded instead of being trusted.
     */
    private static final int MAX_ENTITY_DATA_BYTES = 64 * 1024;
    private static final Set<String> STRING_KEYS = Set.of(
            "id", "CustomName", "variant", "Type", "type", "MainGene", "HiddenGene",
            "sound_variant", "weather_state"
    );
    private static final Set<String> STRING_OR_NUMBER_KEYS = Set.of("Variant");
    private static final Set<String> LIST_KEYS = Set.of(
            "Tags", "attributes", "active_effects", "stew_effects"
    );
    private static final Set<String> COMPOUND_KEYS = Set.of(
            "equipment", "drop_chances", "VillagerData", "carriedBlockState"
    );
    private static final Set<String> NUMBER_KEYS = Set.of(
            // Harmless base/living state.
            "CustomNameVisible", "Silent", "NoGravity", "Invulnerable", "Glowing", "HasVisualFire",
            "Air", "Fire", "OnGround", "fall_distance", "TicksFrozen", "Health",
            "AbsorptionAmount", "FallFlying", "CanPickUpLoot", "PersistenceRequired", "LeftHanded",
            "NoAI",

            // Age and numeric appearance variants.
            "Age", "ForcedAge", "IsBaby", "baby", "RabbitType", "Color", "CollarColor", "Size",
            "size", "Strength", "Xp", "next_weather_age",

            // Other self-contained vanilla properties (never positions or entity references).
            "AttachFace", "BatFlags", "CanBreakDoors", "CannotBeHunted", "CannotHunt",
            "CropsGrownSincePollination", "Crouching", "DespawnDelay", "DrownedConversionTime",
            "DuplicationCooldown", "EatingHaystack", "EggLayTime", "ExplosionPower",
            "ExplosionRadius", "FromBucket", "Fuse", "GotFish", "HasLeftHorn", "HasNectar",
            "HasRightHorn", "HasStung", "HuntingCooldown", "InWaterTime", "IsChickenJockey",
            "IsImmuneToZombification", "IsScreamingGoat", "Johnny", "Lifetime", "Moistness",
            "MoreCarrotTicks", "Peek", "PlayerCreated", "PuffState", "Pumpkin", "Sheared",
            "Sleeping", "SpellTicks", "StrayConversionTime", "TimeInOverworld", "Trusting",
            "Warmup", "covered", "has_egg", "ignited", "life_ticks", "powered", "scute_time",
            "sheared", "suffocating", "wasOnGround"
    );

    public SpawnerEntitySnapshot {
        entityData = sanitize(entityData);
    }

    public static Optional<SpawnerEntitySnapshot> capture(ServerWorld world, MobEntity source) {
        ErrorReporter.Impl reporter = new ErrorReporter.Impl();
        NbtWriteView writeView = NbtWriteView.create(reporter, world.getRegistryManager());
        if (!source.saveSelfData(writeView)) {
            return Optional.empty();
        }
        if (!reporter.isEmpty()) {
            ChunkCopyMod.LOGGER.warn("Errors while snapshotting spawner mob {}: {}",
                    source.getType(), reporter.getErrorsAsString());
        }

        NbtCompound data = sanitize(writeView.getNbt());
        if (!data.contains("id")) {
            ChunkCopyMod.LOGGER.warn("Spawner mob {} did not produce a safe entity id", source.getType());
            return Optional.empty();
        }

        int sourceChunkX = Math.floorDiv(MathHelper.floor(source.getX()), 16);
        int sourceChunkZ = Math.floorDiv(MathHelper.floor(source.getZ()), 16);
        return Optional.of(new SpawnerEntitySnapshot(
                data,
                source.getX() - (sourceChunkX << 4),
                source.getY(),
                source.getZ() - (sourceChunkZ << 4),
                source.getYaw(),
                source.getPitch(),
                ChunkPos.toLong(sourceChunkX, sourceChunkZ)
        ));
    }

    @Override
    public NbtCompound entityData() {
        return entityData.copy();
    }

    static NbtCompound sanitize(NbtCompound source) {
        NbtCompound safe = new NbtCompound();
        for (String key : source.getKeys()) {
            NbtElement value = source.get(key);
            if (value != null && hasExpectedType(key, value)) {
                long projectedSize = (long) safe.getSizeInBytes()
                        + value.getSizeInBytes()
                        + (long) key.length() * 2;
                if (projectedSize <= MAX_ENTITY_DATA_BYTES) {
                    safe.put(key, value.copy());
                }
            }
        }
        return safe;
    }

    private static boolean hasExpectedType(String key, NbtElement value) {
        if (STRING_KEYS.contains(key)) {
            return value instanceof NbtString;
        }
        if (STRING_OR_NUMBER_KEYS.contains(key)) {
            return value instanceof NbtString || value.asNumber().isPresent();
        }
        if (NUMBER_KEYS.contains(key)) {
            return value.asNumber().isPresent();
        }
        if (LIST_KEYS.contains(key)) {
            return value instanceof NbtList;
        }
        return COMPOUND_KEYS.contains(key) && value instanceof NbtCompound;
    }

    public Entity create(ServerWorld world, long targetChunk) {
        ChunkPos chunk = new ChunkPos(targetChunk);
        double targetX = chunk.getStartX() + localX;
        double targetZ = chunk.getStartZ() + localZ;
        Entity loaded = EntityType.loadEntityWithPassengers(
                entityData.copy(),
                world,
                SpawnReason.SPAWNER,
                entity -> {
                    entity.setUuid(UUID.randomUUID());
                    entity.refreshPositionAndAngles(targetX, y, targetZ, yaw, pitch);
                    return entity;
                }
        );
        if (loaded != null) {
            SpawnerCloneMarker.mark(loaded);
        }
        return loaded;
    }
}
