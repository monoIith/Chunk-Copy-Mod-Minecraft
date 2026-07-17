package dev.chunkcopy.replication;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnerEntitySnapshotTest {
    @Test
    void usesStrictAllowlistForSpawnerMobData() {
        NbtCompound source = new NbtCompound();
        source.putString("id", "minecraft:zombie");
        source.putFloat("Health", 7.0F);
        source.putBoolean("IsBaby", true);
        source.putString("variant", "minecraft:temperate");
        source.put("equipment", new NbtCompound());
        source.put("active_effects", new NbtList());
        source.put("Air", new NbtCompound());
        source.put("drop_chances", new NbtList());

        List.of(
                "UUID", "Passengers", "Brain", "ConversionPlayer", "Gossips", "anger", "listener",
                "data", "Owner", "Trusted", "home_pos", "patrol_target", "hive_pos", "flower_pos",
                "anchor_pos", "bound_pos", "PortalCooldown", "portal", "Team"
        ).forEach(key -> source.put(key, new NbtCompound()));

        NbtCompound safe = SpawnerEntitySnapshot.sanitize(source);
        assertTrue(safe.contains("id"));
        assertTrue(safe.contains("Health"));
        assertTrue(safe.contains("IsBaby"));
        assertTrue(safe.contains("variant"));
        assertTrue(safe.contains("equipment"));
        assertTrue(safe.contains("active_effects"));
        assertFalse(safe.contains("Air"));
        assertFalse(safe.contains("drop_chances"));
        for (String forbidden : List.of(
                "UUID", "Passengers", "Brain", "ConversionPlayer", "Gossips", "anger", "listener",
                "data", "Owner", "Trusted", "home_pos", "patrol_target", "hive_pos", "flower_pos",
                "anchor_pos", "bound_pos", "PortalCooldown", "portal", "Team"
        )) {
            assertFalse(safe.contains(forbidden), () -> "unsafe key survived: " + forbidden);
        }
    }
}
