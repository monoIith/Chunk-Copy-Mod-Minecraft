package dev.chunkcopy.replication;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Immutable result of one outermost logical player interaction. */
public record CapturedAction(
        ServerWorld world,
        ServerPlayerEntity player,
        List<RootMutation> roots,
        Set<BlockPos> touchedPositions,
        Set<BlockPos> displayMetadataPositions,
        boolean oversized
) {
    public CapturedAction {
        roots = List.copyOf(roots);
        touchedPositions = Collections.unmodifiableSet(new LinkedHashSet<>(touchedPositions));
        displayMetadataPositions = Collections.unmodifiableSet(new LinkedHashSet<>(displayMetadataPositions));
    }
}
