package dev.chunkcopy.replication;

import dev.chunkcopy.ChunkCopyMod;
import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.function.Supplier;
import java.util.function.Predicate;

/**
 * Thread-confined causal capture for an outermost server player interaction.
 *
 * <p>The mutation depth is incremented around the body of World#setBlockState. A write entered at
 * zero is therefore authored directly by the interaction; writes made by its callbacks are
 * downstream consequences.</p>
 */
public final class PlayerActionContext {
    private static final ThreadLocal<Capture> CURRENT = new ThreadLocal<>();

    private PlayerActionContext() {
    }

    public static <T> T run(ServerPlayerEntity player, ServerWorld world, Supplier<T> operation) {
        return run(player, world, operation, ignored -> true);
    }

    public static <T> T run(
            ServerPlayerEntity player,
            ServerWorld world,
            Supplier<T> operation,
            Predicate<T> successfulResult
    ) {
        if (ReplicationGuard.isActive()
                || player.isSpectator()
                || !ChunkCopyMod.isReady()
                || !ChunkCopyMod.service().isEnabled(world)) {
            return operation.get();
        }

        Capture existing = CURRENT.get();
        if (existing != null) {
            existing.actionDepth++;
            try {
                return operation.get();
            } finally {
                existing.actionDepth--;
            }
        }

        Capture capture = new Capture(
                world,
                player,
                ChunkCopyMod.config().maxCapturedPositionsPerAction
        );
        CURRENT.set(capture);
        try {
            T result = operation.get();
            CURRENT.remove();
            if (successfulResult.test(result)
                    && (!capture.roots.isEmpty() || !capture.metadataTouched.isEmpty() || capture.oversized)) {
                ChunkCopyMod.service().accept(capture.freeze());
            }
            return result;
        } catch (RuntimeException | Error exception) {
            CURRENT.remove();
            throw exception;
        }
    }

    public static void touchDisplayMetadata(ServerWorld world, BlockPos pos) {
        Capture capture = CURRENT.get();
        if (capture == null || capture.world != world || ReplicationGuard.isActive()) {
            return;
        }
        capture.touchMetadata(pos);
    }

    public static MutationToken enterMutation(
            ServerWorld world,
            BlockPos pos,
            BlockState state,
            int flags,
            int maxUpdateDepth
    ) {
        Capture capture = CURRENT.get();
        if (capture == null || capture.world != world || ReplicationGuard.isActive()) {
            return MutationToken.INACTIVE;
        }

        boolean root = capture.mutationDepth.enter();
        BlockState previousState = world.getBlockState(pos);
        return new MutationToken(capture, root, pos, previousState, state, flags, maxUpdateDepth);
    }

    private static final class Capture {
        private final ServerWorld world;
        private final ServerPlayerEntity player;
        private final int positionLimit;
        private final ArrayList<RootMutation> roots = new ArrayList<>();
        private final LinkedHashSet<BlockPos> touched = new LinkedHashSet<>();
        private final LinkedHashSet<BlockPos> metadataTouched = new LinkedHashSet<>();
        private int actionDepth;
        private final MutationDepthTracker mutationDepth = new MutationDepthTracker();
        private boolean oversized;

        private Capture(ServerWorld world, ServerPlayerEntity player, int positionLimit) {
            this.world = world;
            this.player = player;
            this.positionLimit = positionLimit;
        }

        private void successfulMutation(
                boolean root,
                BlockPos pos,
                BlockState previousState,
                BlockState state,
                int flags,
                int maxUpdateDepth
        ) {
            if (oversized) {
                return;
            }
            if (!MutationCapturePolicy.shouldCapture(previousState, state)) {
                return;
            }

            BlockPos immutable = pos.toImmutable();
            touched.add(immutable);
            if (touched.size() > positionLimit) {
                oversized = true;
                roots.clear();
                touched.clear();
                return;
            }
            if (root) {
                roots.add(new RootMutation(immutable, state, flags, maxUpdateDepth));
            }
        }

        private void touchMetadata(BlockPos pos) {
            if (oversized) {
                return;
            }
            BlockPos immutable = pos.toImmutable();
            touched.add(immutable);
            metadataTouched.add(immutable);
            if (touched.size() > positionLimit) {
                oversized = true;
                roots.clear();
                touched.clear();
                metadataTouched.clear();
            }
        }

        private CapturedAction freeze() {
            return new CapturedAction(world, player, roots, touched, metadataTouched, oversized);
        }
    }

    public static final class MutationToken implements AutoCloseable {
        private static final MutationToken INACTIVE = new MutationToken();

        private final Capture capture;
        private final boolean root;
        private final BlockPos pos;
        private final BlockState previousState;
        private final BlockState state;
        private final int flags;
        private final int maxUpdateDepth;
        private boolean completed;
        private boolean closed;

        private MutationToken() {
            capture = null;
            root = false;
            pos = null;
            previousState = null;
            state = null;
            flags = 0;
            maxUpdateDepth = 0;
        }

        private MutationToken(
                Capture capture,
                boolean root,
                BlockPos pos,
                BlockState previousState,
                BlockState state,
                int flags,
                int maxUpdateDepth
        ) {
            this.capture = capture;
            this.root = root;
            this.pos = pos;
            this.previousState = previousState;
            this.state = state;
            this.flags = flags;
            this.maxUpdateDepth = maxUpdateDepth;
        }

        public void complete(boolean success) {
            if (capture != null && success && !completed) {
                completed = true;
                capture.successfulMutation(root, pos, previousState, state, flags, maxUpdateDepth);
            }
        }

        @Override
        public void close() {
            if (capture != null && !closed) {
                closed = true;
                capture.mutationDepth.leave();
            }
        }
    }
}
