package dev.chunkcopy.replication;

import net.minecraft.entity.Entity;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Non-persistent marker used to make the spawner-clone exclusion explicit. */
public final class SpawnerCloneMarker {
    private static final Set<Entity> CLONES = Collections.newSetFromMap(new WeakHashMap<>());

    private SpawnerCloneMarker() {
    }

    public static void mark(Entity entity) {
        CLONES.add(entity);
    }

    public static boolean isMarked(Entity entity) {
        return CLONES.contains(entity);
    }
}
