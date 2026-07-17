package dev.chunkcopy.state;

import com.mojang.serialization.Codec;
import dev.chunkcopy.ChunkCopyMod;
import dev.chunkcopy.replication.CapturedAction;
import dev.chunkcopy.replication.ReplicationMode;
import dev.chunkcopy.replication.SanitizedBlockEntityData;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Per-dimension settings and persistent final-state overlay. */
public final class ChunkCopyWorldState extends PersistentState {
    private static final Codec<ChunkCopyWorldState> CODEC = NbtCompound.CODEC.xmap(
            ChunkCopyWorldState::fromNbt,
            ChunkCopyWorldState::toNbt
    );
    private static final PersistentStateType<ChunkCopyWorldState> TYPE = new PersistentStateType<>(
            "chunkcopy_overlay",
            ChunkCopyWorldState::new,
            CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE
    );

    private boolean enabled;
    private ReplicationMode mode;
    private long revision;
    private final Map<LocalSlot, OverlayEntry> overlay = new LinkedHashMap<>();
    private final Map<Long, Map<LocalSlot, Long>> appliedRevisions = new HashMap<>();

    public ChunkCopyWorldState() {
        enabled = ChunkCopyMod.config().defaultEnabled;
        mode = ReplicationMode.parse(ChunkCopyMod.config().defaultMode);
    }

    public static ChunkCopyWorldState get(ServerWorld world) {
        return world.getPersistentStateManager().getOrCreate(TYPE);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled != enabled) {
            this.enabled = enabled;
            markDirty();
        }
    }

    public ReplicationMode mode() {
        return mode;
    }

    public void setMode(ReplicationMode mode) {
        if (this.mode != mode) {
            this.mode = mode;
            markDirty();
        }
    }

    public long revision() {
        return revision;
    }

    public int overlaySize() {
        return overlay.size();
    }

    public RevisionBatch recordFinalStates(CapturedAction action) {
        revision++;
        ArrayList<SlotRevision> revisions = new ArrayList<>();
        for (BlockPos sourcePos : action.touchedPositions()) {
            revisions.add(recordPosition(action.world(), sourcePos));
        }
        markDirty();
        return new RevisionBatch(revision, List.copyOf(revisions));
    }

    public RevisionBatch recordFinalState(ServerWorld world, BlockPos sourcePos) {
        revision++;
        SlotRevision recorded = recordPosition(world, sourcePos);
        markDirty();
        return new RevisionBatch(revision, List.of(recorded));
    }

    private SlotRevision recordPosition(ServerWorld world, BlockPos sourcePos) {
        LocalSlot slot = LocalSlot.from(sourcePos);
        Optional<SanitizedBlockEntityData> metadata =
                SanitizedBlockEntityData.capture(world, sourcePos);
        overlay.put(slot, new OverlayEntry(world.getBlockState(sourcePos), metadata, revision));
        markAppliedInternal(ChunkPos.toLong(sourcePos), slot, revision);
        return new SlotRevision(slot, revision);
    }

    public List<PendingEntry> pendingFor(long packedChunk) {
        Map<LocalSlot, Long> applied = appliedRevisions.get(packedChunk);
        ArrayList<PendingEntry> pending = new ArrayList<>();
        for (Map.Entry<LocalSlot, OverlayEntry> item : overlay.entrySet()) {
            long appliedRevision = applied == null ? 0 : applied.getOrDefault(item.getKey(), 0L);
            if (appliedRevision < item.getValue().revision()) {
                pending.add(new PendingEntry(item.getKey(), item.getValue()));
            }
        }
        return List.copyOf(pending);
    }

    public void markApplied(long packedChunk, List<SlotRevision> revisions) {
        for (SlotRevision item : revisions) {
            markAppliedInternal(packedChunk, item.slot(), item.revision());
        }
        if (!revisions.isEmpty()) {
            markDirty();
        }
    }

    public void markApplied(long packedChunk, LocalSlot slot, long appliedRevision) {
        markAppliedInternal(packedChunk, slot, appliedRevision);
        markDirty();
    }

    public void resetOverlay() {
        overlay.clear();
        appliedRevisions.clear();
        revision++;
        markDirty();
    }

    private void markAppliedInternal(long packedChunk, LocalSlot slot, long appliedRevision) {
        appliedRevisions
                .computeIfAbsent(packedChunk, ignored -> new HashMap<>())
                .merge(slot, appliedRevision, Math::max);
    }

    private NbtCompound toNbt() {
        NbtCompound root = new NbtCompound();
        root.putBoolean("enabled", enabled);
        root.putString("mode", mode.serializedName());
        root.putLong("revision", revision);

        NbtList entries = new NbtList();
        for (Map.Entry<LocalSlot, OverlayEntry> item : overlay.entrySet()) {
            NbtCompound encoded = encodeSlot(item.getKey());
            encoded.putLong("revision", item.getValue().revision());
            encodeBlockState(encoded, item.getValue().state());
            item.getValue().metadata().ifPresent(metadata -> {
                encoded.putString("metadata_kind", metadata.kind().name());
                encoded.put("metadata", metadata.data());
            });
            entries.add(encoded);
        }
        root.put("overlay", entries);

        NbtList chunks = new NbtList();
        for (Map.Entry<Long, Map<LocalSlot, Long>> chunk : appliedRevisions.entrySet()) {
            NbtCompound encodedChunk = new NbtCompound();
            encodedChunk.putLong("chunk", chunk.getKey());
            NbtList slots = new NbtList();
            for (Map.Entry<LocalSlot, Long> applied : chunk.getValue().entrySet()) {
                NbtCompound encoded = encodeSlot(applied.getKey());
                encoded.putLong("revision", applied.getValue());
                slots.add(encoded);
            }
            encodedChunk.put("slots", slots);
            chunks.add(encodedChunk);
        }
        root.put("applied", chunks);
        return root;
    }

    private static ChunkCopyWorldState fromNbt(NbtCompound root) {
        ChunkCopyWorldState state = new ChunkCopyWorldState();
        state.enabled = root.getBoolean("enabled", state.enabled);
        state.mode = ReplicationMode.parse(root.getString("mode", state.mode.serializedName()));
        state.revision = root.getLong("revision", 0L);

        NbtList entries = root.getListOrEmpty("overlay");
        for (int index = 0; index < entries.size(); index++) {
            NbtCompound encoded = entries.getCompoundOrEmpty(index);
            try {
                LocalSlot slot = decodeSlot(encoded);
                Optional<BlockState> blockState = decodeBlockState(encoded);
                if (blockState.isEmpty()) {
                    continue;
                }
                Optional<SanitizedBlockEntityData> metadata = decodeMetadata(encoded);
                state.overlay.put(slot, new OverlayEntry(
                        blockState.get(),
                        metadata,
                        encoded.getLong("revision", 0L)
                ));
            } catch (RuntimeException exception) {
                ChunkCopyMod.LOGGER.warn("Skipping malformed Chunk Copy overlay record {}", index, exception);
            }
        }

        NbtList chunks = root.getListOrEmpty("applied");
        for (int index = 0; index < chunks.size(); index++) {
            NbtCompound encodedChunk = chunks.getCompoundOrEmpty(index);
            long packedChunk = encodedChunk.getLong("chunk", 0L);
            NbtList slots = encodedChunk.getListOrEmpty("slots");
            for (int slotIndex = 0; slotIndex < slots.size(); slotIndex++) {
                NbtCompound encoded = slots.getCompoundOrEmpty(slotIndex);
                try {
                    state.markAppliedInternal(
                            packedChunk,
                            decodeSlot(encoded),
                            encoded.getLong("revision", 0L)
                    );
                } catch (RuntimeException exception) {
                    ChunkCopyMod.LOGGER.warn("Skipping malformed Chunk Copy applied-revision record", exception);
                }
            }
        }
        return state;
    }

    private static NbtCompound encodeSlot(LocalSlot slot) {
        NbtCompound encoded = new NbtCompound();
        encoded.putByte("x", (byte) slot.x());
        encoded.putInt("y", slot.y());
        encoded.putByte("z", (byte) slot.z());
        return encoded;
    }

    private static LocalSlot decodeSlot(NbtCompound encoded) {
        return new LocalSlot(
                encoded.getByte("x", (byte) -1),
                encoded.getInt("y", Integer.MIN_VALUE),
                encoded.getByte("z", (byte) -1)
        );
    }

    private static void encodeBlockState(NbtCompound encoded, BlockState state) {
        encoded.putString("block", Registries.BLOCK.getId(state.getBlock()).toString());
        NbtCompound properties = new NbtCompound();
        for (Property<?> property : state.getProperties()) {
            properties.putString(property.getName(), propertyValueName(state, property));
        }
        encoded.put("properties", properties);
    }

    private static Optional<BlockState> decodeBlockState(NbtCompound encoded) {
        Identifier id = Identifier.tryParse(encoded.getString("block", ""));
        if (id == null) {
            ChunkCopyMod.LOGGER.warn("Skipping Chunk Copy overlay state with invalid block id");
            return Optional.empty();
        }
        Optional<Block> block = Registries.BLOCK.getOptionalValue(id);
        if (block.isEmpty()) {
            ChunkCopyMod.LOGGER.warn("Skipping Chunk Copy overlay state for unknown block {}", id);
            return Optional.empty();
        }

        BlockState state = block.get().getDefaultState();
        NbtCompound properties = encoded.getCompoundOrEmpty("properties");
        for (String name : properties.getKeys()) {
            Property<?> property = state.getProperties().stream()
                    .filter(candidate -> candidate.getName().equals(name))
                    .findFirst()
                    .orElse(null);
            if (property == null) {
                ChunkCopyMod.LOGGER.warn("Ignoring unknown property {} for block {}", name, id);
                continue;
            }
            state = withParsedProperty(state, property, properties.getString(name, ""));
        }
        return Optional.of(state);
    }

    private static Optional<SanitizedBlockEntityData> decodeMetadata(NbtCompound encoded) {
        String kindName = encoded.getString("metadata_kind", "");
        if (kindName.isEmpty()) {
            return Optional.empty();
        }
        try {
            SanitizedBlockEntityData.Kind kind = SanitizedBlockEntityData.Kind.valueOf(kindName);
            return Optional.of(new SanitizedBlockEntityData(kind, encoded.getCompoundOrEmpty("metadata")));
        } catch (IllegalArgumentException exception) {
            ChunkCopyMod.LOGGER.warn("Ignoring unknown Chunk Copy metadata kind {}", kindName);
            return Optional.empty();
        }
    }

    private static <T extends Comparable<T>> String propertyValueName(BlockState state, Property<T> property) {
        return property.name(state.get(property));
    }

    private static <T extends Comparable<T>> BlockState withParsedProperty(
            BlockState state,
            Property<T> property,
            String encoded
    ) {
        return property.parse(encoded).map(value -> state.with(property, value)).orElseGet(() -> {
            ChunkCopyMod.LOGGER.warn("Ignoring invalid value {} for property {}", encoded, property.getName());
            return state;
        });
    }

    public record OverlayEntry(
            BlockState state,
            Optional<SanitizedBlockEntityData> metadata,
            long revision
    ) {
    }

    public record PendingEntry(LocalSlot slot, OverlayEntry entry) {
    }

    public record SlotRevision(LocalSlot slot, long revision) {
    }

    public record RevisionBatch(long revision, List<SlotRevision> slots) {
    }
}
