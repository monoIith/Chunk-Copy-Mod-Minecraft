package dev.chunkcopy.replication;

import dev.chunkcopy.ChunkCopyIds;
import dev.chunkcopy.config.ChunkCopyConfig;
import dev.chunkcopy.state.ChunkCopyWorldState;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.block.BlockState;
import net.minecraft.block.Block;
import net.minecraft.block.CarvedPumpkinBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkLevelType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;

/** Owns loaded-chunk indexes and the bounded live block-replication queue. */
public final class ChunkCopyService {
    private final ChunkCopyConfig config;
    private final Logger logger;
    private final Map<ServerWorld, LongSet> loadedChunks = new IdentityHashMap<>();
    private final Map<ServerWorld, LongSet> pendingCatchups = new IdentityHashMap<>();
    private final ArrayDeque<QueuedBlockWrite> blockQueue = new ArrayDeque<>();
    private final ArrayDeque<EntityReplica> entityQueue = new ArrayDeque<>();

    public ChunkCopyService(ChunkCopyConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    public boolean isEnabled(ServerWorld world) {
        return ChunkCopyWorldState.get(world).isEnabled();
    }

    public ReplicationMode mode(ServerWorld world) {
        return ChunkCopyWorldState.get(world).mode();
    }

    public void onChunkLoad(ServerWorld world, WorldChunk chunk) {
        long packed = chunk.getPos().toLong();
        loadedChunks.computeIfAbsent(world, ignored -> new LongOpenHashSet()).add(packed);
        pendingCatchups.computeIfAbsent(world, ignored -> new LongOpenHashSet()).add(packed);
    }

    public void onChunkUnload(ServerWorld world, WorldChunk chunk) {
        LongSet chunks = loadedChunks.get(world);
        if (chunks != null) {
            chunks.remove(chunk.getPos().toLong());
        }
        LongSet catchups = pendingCatchups.get(world);
        if (catchups != null) {
            catchups.remove(chunk.getPos().toLong());
        }
    }

    public void onChunkLevelChange(
            ServerWorld world,
            WorldChunk chunk,
            ChunkLevelType oldLevel,
            ChunkLevelType newLevel
    ) {
        LongSet chunks = loadedChunks.computeIfAbsent(world, ignored -> new LongOpenHashSet());
        if (newLevel == ChunkLevelType.INACCESSIBLE) {
            chunks.remove(chunk.getPos().toLong());
        } else {
            long packed = chunk.getPos().toLong();
            chunks.add(packed);
            pendingCatchups.computeIfAbsent(world, ignored -> new LongOpenHashSet()).add(packed);
        }
    }

    public void accept(CapturedAction action) {
        if (action.oversized()) {
            reject(action.player(), "captured more than " + config.maxCapturedPositionsPerAction + " positions");
            return;
        }

        LongSet loaded = loadedChunks.get(action.world());
        LongArrayList targets = loaded == null ? new LongArrayList() : new LongArrayList(loaded);
        LinkedHashSet<BlockPos> metadataOnly = new LinkedHashSet<>(action.displayMetadataPositions());
        for (RootMutation root : action.roots()) {
            metadataOnly.remove(root.sourcePos());
        }
        long estimatedWrites = 0;
        for (long targetChunk : targets) {
            for (RootMutation root : action.roots()) {
                if (targetChunk != ChunkPos.toLong(root.sourcePos())) {
                    estimatedWrites++;
                }
            }
            for (BlockPos sourcePos : metadataOnly) {
                if (targetChunk != ChunkPos.toLong(sourcePos)) {
                    estimatedWrites++;
                }
            }
        }

        if (!QueueAdmission.fits(blockQueue.size(), estimatedWrites, config.maxQueuedBlockWrites)) {
            reject(action.player(), "block replica queue would exceed " + config.maxQueuedBlockWrites + " writes");
            return;
        }

        ChunkCopyWorldState worldState = ChunkCopyWorldState.get(action.world());
        ChunkCopyWorldState.RevisionBatch persistence = worldState.mode() == ReplicationMode.PERSISTENT
                ? worldState.recordFinalStates(action)
                : null;

        for (long targetChunk : targets) {
            ArrayList<RootMutation> targetRoots = new ArrayList<>();
            for (RootMutation root : action.roots()) {
                if (targetChunk == ChunkPos.toLong(root.sourcePos())) {
                    continue;
                }
                targetRoots.add(root);
            }
            ArrayList<BlockPos> targetMetadata = new ArrayList<>();
            for (BlockPos sourcePos : metadataOnly) {
                if (targetChunk != ChunkPos.toLong(sourcePos)) {
                    targetMetadata.add(sourcePos);
                }
            }
            int targetWrites = targetRoots.size() + targetMetadata.size();
            if (targetWrites == 0) {
                continue;
            }

            TargetProgress progress = persistence == null
                    ? null
                    : new TargetProgress(worldState, targetChunk, persistence.slots(), targetWrites);
            for (RootMutation root : targetRoots) {
                Optional<SanitizedBlockEntityData> metadata =
                        SanitizedBlockEntityData.capture(action.world(), root.sourcePos());
                blockQueue.addLast(new LiveReplica(action.world(), targetChunk, root, metadata, progress));
            }
            for (BlockPos sourcePos : targetMetadata) {
                Optional<SanitizedBlockEntityData> metadata =
                        SanitizedBlockEntityData.capture(action.world(), sourcePos);
                if (metadata.isPresent()) {
                        blockQueue.addLast(new MetadataReplica(
                                action.world(), targetChunk, sourcePos, metadata.get(), progress));
                } else if (progress != null) {
                    progress.finished(true);
                }
            }
        }
    }

    public void acceptDisplayMetadata(ServerWorld world, BlockPos sourcePos, ServerPlayerEntity player) {
        if (player.isSpectator() || !isEnabled(world)) {
            return;
        }
        Optional<SanitizedBlockEntityData> metadata = SanitizedBlockEntityData.capture(world, sourcePos);
        if (metadata.isEmpty()) {
            return;
        }
        LongSet loaded = loadedChunks.get(world);
        LongArrayList targets = new LongArrayList();
        if (loaded != null) {
            for (long target : loaded) {
                if (target != ChunkPos.toLong(sourcePos)) {
                    targets.add(target);
                }
            }
        }
        if (!QueueAdmission.fits(blockQueue.size(), targets.size(), config.maxQueuedBlockWrites)) {
            reject(player, "block replica queue would exceed " + config.maxQueuedBlockWrites + " writes");
            return;
        }

        ChunkCopyWorldState state = ChunkCopyWorldState.get(world);
        ChunkCopyWorldState.RevisionBatch persistence = state.mode() == ReplicationMode.PERSISTENT
                ? state.recordFinalState(world, sourcePos)
                : null;
        for (long target : targets) {
            TargetProgress progress = persistence == null
                    ? null
                    : new TargetProgress(state, target, persistence.slots(), 1);
            blockQueue.addLast(new MetadataReplica(world, target, sourcePos, metadata.get(), progress));
        }
    }

    public void tick(MinecraftServer server) {
        enqueuePendingCatchups();
        int budget = config.maxBlockWritesPerTick;
        while (budget-- > 0) {
            QueuedBlockWrite write = blockQueue.pollFirst();
            if (write == null) {
                break;
            }
            try {
                if (write instanceof LiveReplica live) {
                    applyLive(live);
                } else if (write instanceof MetadataReplica metadata) {
                    applyMetadata(metadata);
                } else if (write instanceof CatchupReplica catchup) {
                    applyCatchup(catchup);
                }
            } catch (RuntimeException exception) {
                logger.error("Chunk Copy replica write failed", exception);
                if (write instanceof LiveReplica live && live.progress() != null) {
                    live.progress().finished(false);
                } else if (write instanceof MetadataReplica metadata && metadata.progress() != null) {
                    metadata.progress().finished(false);
                } else if (write instanceof CatchupReplica catchup) {
                    scheduleCatchupRetry(catchup);
                }
            }
        }

        int entityBudget = config.maxSpawnerClonesPerTick;
        while (entityBudget-- > 0) {
            EntityReplica replica = entityQueue.pollFirst();
            if (replica == null) {
                break;
            }
            try {
                applyEntityClone(replica);
            } catch (RuntimeException exception) {
                logger.error("Chunk Copy spawner clone failed", exception);
            }
        }
    }

    public int queuedBlockWrites() {
        return blockQueue.size();
    }

    public int queuedEntityClones() {
        return entityQueue.size();
    }

    public void acceptSpawnerSpawn(ServerWorld world, MobEntity source) {
        if (!config.mirrorMonsterSpawnerSpawns || !isEnabled(world) || SpawnerCloneMarker.isMarked(source)) {
            return;
        }
        Optional<SpawnerEntitySnapshot> snapshot = SpawnerEntitySnapshot.capture(world, source);
        if (snapshot.isEmpty()) {
            logger.warn("Could not snapshot successful spawner mob {}", source.getType());
            return;
        }

        LongSet loaded = loadedChunks.get(world);
        if (loaded == null || loaded.isEmpty()) {
            return;
        }
        LongArrayList targets = new LongArrayList();
        for (long packedChunk : loaded) {
            if (packedChunk != snapshot.get().sourceChunk()) {
                targets.add(packedChunk);
            }
        }
        if (!QueueAdmission.fits(entityQueue.size(), targets.size(), config.maxQueuedEntityClones)) {
            rejectSpawner(world, source.getBlockPos(),
                    "entity clone queue would exceed " + config.maxQueuedEntityClones + " entries");
            return;
        }
        for (long packedChunk : targets) {
            entityQueue.addLast(new EntityReplica(world, packedChunk, snapshot.get()));
        }
    }

    public int loadedChunkCount(ServerWorld world) {
        LongSet chunks = loadedChunks.get(world);
        return chunks == null ? 0 : chunks.size();
    }

    /** Drops all runtime-only references when an integrated or dedicated server stops. */
    public void clearRuntimeState() {
        loadedChunks.clear();
        pendingCatchups.clear();
        blockQueue.clear();
        entityQueue.clear();
    }

    public void setEnabled(ServerWorld world, boolean enabled) {
        ChunkCopyWorldState.get(world).setEnabled(enabled);
        if (!enabled) {
            blockQueue.removeIf(write -> write.world() == world);
            entityQueue.removeIf(replica -> replica.world() == world);
            LongSet catchups = pendingCatchups.get(world);
            if (catchups != null) {
                catchups.clear();
            }
        } else if (mode(world) == ReplicationMode.PERSISTENT) {
            scheduleAllLoadedForCatchup(world);
        }
    }

    public void setMode(ServerWorld world, ReplicationMode mode) {
        ChunkCopyWorldState.get(world).setMode(mode);
        if (mode == ReplicationMode.PERSISTENT) {
            scheduleAllLoadedForCatchup(world);
        } else {
            blockQueue.removeIf(write -> write instanceof CatchupReplica catchup && catchup.world() == world);
            LongSet catchups = pendingCatchups.get(world);
            if (catchups != null) {
                catchups.clear();
            }
        }
    }

    public void resetPersistent(ServerWorld world) {
        ChunkCopyWorldState.get(world).resetOverlay();
        blockQueue.removeIf(write -> write instanceof CatchupReplica catchup && catchup.world() == world);
        LongSet catchups = pendingCatchups.get(world);
        if (catchups != null) {
            catchups.clear();
        }
    }

    private void applyLive(LiveReplica replica) {
        ServerWorld world = replica.world();
        if (!isResident(world, replica.targetChunk())) {
            finish(replica, false);
            return;
        }

        BlockPos target = PositionMapping.inChunk(replica.root().sourcePos(), replica.targetChunk());
        if (!world.isInBuildLimit(target) || !world.getWorldBorder().contains(target)) {
            finish(replica, true);
            return;
        }

        BlockState current = world.getBlockState(target);
        BlockState proposed = replica.root().state();
        if (current.isIn(ChunkCopyIds.PROTECTED_BLOCKS) || proposed.isIn(ChunkCopyIds.PROTECTED_BLOCKS)) {
            if (config.logSkippedProtectedBlocks) {
                logger.info("Skipped protected Chunk Copy write at {} in {}", target, world.getRegistryKey().getValue());
            }
            finish(replica, true);
            return;
        }
        if (!hasRequiredCallbackNeighborhood(world, target, proposed)) {
            finish(replica, false);
            return;
        }

        boolean applied = ReplicationGuard.call(() -> {
            boolean changed = world.setBlockState(
                    target,
                    proposed,
                    replica.root().flags() | Block.SKIP_DROPS | Block.SKIP_BLOCK_ENTITY_REPLACED_CALLBACK,
                    replica.root().maxUpdateDepth()
            );
            if (!changed && !world.getBlockState(target).equals(proposed)) {
                return false;
            }
            ReplicaBlockEntityReset.reset(world, target, proposed);
            return replica.metadata().map(data -> data.apply(world, target)).orElse(true);
        });
        finish(replica, applied);
    }

    private void applyCatchup(CatchupReplica replica) {
        ServerWorld world = replica.world();
        ChunkCopyWorldState worldState = ChunkCopyWorldState.get(world);
        if (!worldState.isEnabled()
                || worldState.mode() != ReplicationMode.PERSISTENT
                || !isResident(world, replica.targetChunk())) {
            return;
        }

        BlockPos target = replica.pending().slot().inChunk(replica.targetChunk());
        if (!world.isInBuildLimit(target) || !world.getWorldBorder().contains(target)) {
            worldState.markApplied(
                    replica.targetChunk(),
                    replica.pending().slot(),
                    replica.pending().entry().revision()
            );
            return;
        }
        BlockState current = world.getBlockState(target);
        BlockState proposed = replica.pending().entry().state();
        if (current.isIn(ChunkCopyIds.PROTECTED_BLOCKS) || proposed.isIn(ChunkCopyIds.PROTECTED_BLOCKS)) {
            worldState.markApplied(
                    replica.targetChunk(),
                    replica.pending().slot(),
                    replica.pending().entry().revision()
            );
            return;
        }

        boolean applied = ReplicationGuard.call(() -> {
            boolean changed = world.setBlockState(
                    target,
                    proposed,
                    Block.FORCE_STATE_AND_SKIP_CALLBACKS_AND_DROPS | Block.NOTIFY_LISTENERS,
                    512
            );
            if (!changed && !world.getBlockState(target).equals(proposed)) {
                return false;
            }
            ReplicaBlockEntityReset.reset(world, target, proposed);
            boolean metadataApplied = replica.pending().entry().metadata()
                    .map(data -> data.apply(world, target))
                    .orElse(true);
            world.getChunkManager().markForUpdate(target);
            return metadataApplied;
        });
        if (applied) {
            worldState.markApplied(
                    replica.targetChunk(),
                    replica.pending().slot(),
                    replica.pending().entry().revision()
            );
        } else {
            scheduleCatchupRetry(replica);
        }
    }

    private void applyMetadata(MetadataReplica replica) {
        ServerWorld world = replica.world();
        if (!isResident(world, replica.targetChunk())) {
            finish(replica, false);
            return;
        }
        BlockPos target = PositionMapping.inChunk(replica.sourcePos(), replica.targetChunk());
        if (!world.isInBuildLimit(target)
                || !world.getWorldBorder().contains(target)
                || world.getBlockState(target).isIn(ChunkCopyIds.PROTECTED_BLOCKS)) {
            finish(replica, true);
            return;
        }
        boolean applied = ReplicationGuard.call(() -> replica.metadata().apply(world, target));
        finish(replica, applied);
    }

    private void applyEntityClone(EntityReplica replica) {
        ServerWorld world = replica.world();
        if (!isEnabled(world) || !isResident(world, replica.targetChunk())) {
            return;
        }
        SpawnerEntitySnapshot snapshot = replica.snapshot();
        ChunkPos chunk = new ChunkPos(replica.targetChunk());
        double x = chunk.getStartX() + snapshot.localX();
        double z = chunk.getStartZ() + snapshot.localZ();
        BlockPos blockPos = BlockPos.ofFloored(x, snapshot.y(), z);
        if (!world.isInBuildLimit(blockPos) || !world.getWorldBorder().contains(x, z)) {
            return;
        }

        Entity clone = snapshot.create(world, replica.targetChunk());
        if (!(clone instanceof MobEntity)) {
            logger.warn("Skipped non-mob entity decoded from a spawner snapshot");
            return;
        }
        ReplicationGuard.run(() -> world.spawnNewEntityAndPassengers(clone));
    }

    private void finish(LiveReplica replica, boolean resident) {
        if (replica.progress() != null) {
            replica.progress().finished(resident);
        }
    }

    private void finish(MetadataReplica replica, boolean resident) {
        if (replica.progress() != null) {
            replica.progress().finished(resident);
        }
    }

    private boolean isResident(ServerWorld world, long packedChunk) {
        LongSet tracked = loadedChunks.get(world);
        return tracked != null
                && tracked.contains(packedChunk)
                && world.getChunkManager().getWorldChunk(
                        ChunkPos.getPackedX(packedChunk),
                        ChunkPos.getPackedZ(packedChunk)
                ) != null;
    }

    /**
     * Golem pattern matching reads a small area without loading it and returns null at an isolated
     * full-chunk fringe. Skip that replica instead of force-loading neighbors or letting vanilla's
     * callback fail after the root state has already changed.
     */
    private boolean hasRequiredCallbackNeighborhood(ServerWorld world, BlockPos target, BlockState proposed) {
        if (!(proposed.getBlock() instanceof CarvedPumpkinBlock)) {
            return true;
        }
        if (!world.isInBuildLimit(target.add(0, -3, 0))
                || !world.isInBuildLimit(target.add(0, 1, 0))) {
            return false;
        }

        int minChunkX = Math.floorDiv(target.getX() - 2, 16);
        int maxChunkX = Math.floorDiv(target.getX() + 2, 16);
        int minChunkZ = Math.floorDiv(target.getZ() - 2, 16);
        int maxChunkZ = Math.floorDiv(target.getZ() + 2, 16);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (world.getChunkManager().getWorldChunk(chunkX, chunkZ) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    private void enqueuePendingCatchups() {
        for (Map.Entry<ServerWorld, LongSet> worldEntry : pendingCatchups.entrySet()) {
            ServerWorld world = worldEntry.getKey();
            ChunkCopyWorldState state = ChunkCopyWorldState.get(world);
            if (!state.isEnabled() || state.mode() != ReplicationMode.PERSISTENT) {
                continue;
            }

            LongIterator iterator = worldEntry.getValue().iterator();
            while (iterator.hasNext()) {
                long packedChunk = iterator.nextLong();
                if (!isResident(world, packedChunk)) {
                    iterator.remove();
                    continue;
                }
                List<ChunkCopyWorldState.PendingEntry> pending = state.pendingFor(packedChunk);
                if (pending.isEmpty()) {
                    iterator.remove();
                    continue;
                }
                if (!QueueAdmission.fits(blockQueue.size(), pending.size(), config.maxQueuedBlockWrites)) {
                    continue;
                }
                for (ChunkCopyWorldState.PendingEntry entry : pending) {
                    blockQueue.addLast(new CatchupReplica(world, packedChunk, entry));
                }
                iterator.remove();
            }
        }
    }

    private void scheduleAllLoadedForCatchup(ServerWorld world) {
        LongSet loaded = loadedChunks.get(world);
        if (loaded != null && !loaded.isEmpty()) {
            pendingCatchups
                    .computeIfAbsent(world, ignored -> new LongOpenHashSet())
                    .addAll(loaded);
        }
    }

    private void scheduleCatchupRetry(CatchupReplica replica) {
        pendingCatchups
                .computeIfAbsent(replica.world(), ignored -> new LongOpenHashSet())
                .add(replica.targetChunk());
    }

    private void reject(ServerPlayerEntity sourcePlayer, String reason) {
        if (config.logQueueOverflows) {
            logger.warn("Rejected Chunk Copy replication from {}: {}", sourcePlayer.getName().getString(), reason);
        }
        Text message = Text.literal("[Chunk Copy] Replication rejected: " + reason);
        for (ServerPlayerEntity player : sourcePlayer.getCommandSource().getServer().getPlayerManager().getPlayerList()) {
            if (player.getCommandSource().hasPermissionLevel(2)) {
                player.sendMessage(message, false);
            }
        }
    }

    private void rejectSpawner(ServerWorld world, BlockPos sourcePos, String reason) {
        if (config.logQueueOverflows) {
            logger.warn("Rejected Chunk Copy spawner clones at {} in {}: {}",
                    sourcePos, world.getRegistryKey().getValue(), reason);
        }
        Text message = Text.literal("[Chunk Copy] Spawner replication rejected: " + reason);
        for (ServerPlayerEntity player : world.getServer().getPlayerManager().getPlayerList()) {
            if (player.getCommandSource().hasPermissionLevel(2)) {
                player.sendMessage(message, false);
            }
        }
    }

    private sealed interface QueuedBlockWrite permits LiveReplica, MetadataReplica, CatchupReplica {
        ServerWorld world();
    }

    private record LiveReplica(
            ServerWorld world,
            long targetChunk,
            RootMutation root,
            Optional<SanitizedBlockEntityData> metadata,
            TargetProgress progress
    ) implements QueuedBlockWrite {
    }

    private record MetadataReplica(
            ServerWorld world,
            long targetChunk,
            BlockPos sourcePos,
            SanitizedBlockEntityData metadata,
            TargetProgress progress
    ) implements QueuedBlockWrite {
    }

    private record CatchupReplica(
            ServerWorld world,
            long targetChunk,
            ChunkCopyWorldState.PendingEntry pending
    ) implements QueuedBlockWrite {
    }

    private record EntityReplica(
            ServerWorld world,
            long targetChunk,
            SpawnerEntitySnapshot snapshot
    ) {
    }

    private static final class TargetProgress {
        private final ChunkCopyWorldState state;
        private final long targetChunk;
        private final List<ChunkCopyWorldState.SlotRevision> revisions;
        private int remaining;
        private boolean resident = true;

        private TargetProgress(
                ChunkCopyWorldState state,
                long targetChunk,
                List<ChunkCopyWorldState.SlotRevision> revisions,
                int remaining
        ) {
            this.state = state;
            this.targetChunk = targetChunk;
            this.revisions = revisions;
            this.remaining = remaining;
        }

        private void finished(boolean resident) {
            this.resident &= resident;
            remaining--;
            if (remaining == 0 && this.resident) {
                state.markApplied(targetChunk, revisions);
            }
        }
    }
}
