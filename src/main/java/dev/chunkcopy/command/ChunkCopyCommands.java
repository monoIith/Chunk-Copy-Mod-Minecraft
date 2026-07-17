package dev.chunkcopy.command;

import com.mojang.brigadier.CommandDispatcher;
import dev.chunkcopy.ChunkCopyMod;
import dev.chunkcopy.replication.ChunkCopyService;
import dev.chunkcopy.replication.ReplicationMode;
import dev.chunkcopy.state.ChunkCopyWorldState;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

/** Permission-level-2 administrative commands. */
public final class ChunkCopyCommands {
    private ChunkCopyCommands() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("chunkcopy")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.literal("status").executes(context -> status(context.getSource())))
                .then(CommandManager.literal("enable").executes(context -> enable(context.getSource(), true)))
                .then(CommandManager.literal("disable").executes(context -> enable(context.getSource(), false)))
                .then(CommandManager.literal("mode")
                        .then(CommandManager.literal("loaded")
                                .executes(context -> mode(context.getSource(), ReplicationMode.LOADED)))
                        .then(CommandManager.literal("persistent")
                                .executes(context -> mode(context.getSource(), ReplicationMode.PERSISTENT))))
                .then(CommandManager.literal("reset-persistent")
                        .then(CommandManager.literal("confirm")
                                .executes(context -> reset(context.getSource())))));
    }

    private static int status(ServerCommandSource source) {
        ServerWorld world = source.getWorld();
        ChunkCopyService service = ChunkCopyMod.service();
        ChunkCopyWorldState state = ChunkCopyWorldState.get(world);
        source.sendFeedback(() -> Text.literal(
                "Chunk Copy: " + (state.isEnabled() ? "enabled" : "disabled")
                        + ", mode=" + state.mode().serializedName()
                        + ", dimension=" + world.getRegistryKey().getValue()
                        + ", loadedChunks=" + service.loadedChunkCount(world)
                        + ", queuedBlockWrites=" + service.queuedBlockWrites()
                        + ", queuedEntityClones=" + service.queuedEntityClones()
                        + ", overlaySlots=" + state.overlaySize()
                        + ", revision=" + state.revision()
        ), false);
        return 1;
    }

    private static int enable(ServerCommandSource source, boolean enabled) {
        ServerWorld world = source.getWorld();
        ChunkCopyMod.service().setEnabled(world, enabled);
        source.sendFeedback(
                () -> Text.literal("Chunk Copy " + (enabled ? "enabled" : "disabled")
                        + " in " + world.getRegistryKey().getValue()),
                true
        );
        return 1;
    }

    private static int mode(ServerCommandSource source, ReplicationMode mode) {
        ServerWorld world = source.getWorld();
        ChunkCopyMod.service().setMode(world, mode);
        source.sendFeedback(
                () -> Text.literal("Chunk Copy mode set to " + mode.serializedName()
                        + " in " + world.getRegistryKey().getValue()),
                true
        );
        return 1;
    }

    private static int reset(ServerCommandSource source) {
        ServerWorld world = source.getWorld();
        ChunkCopyMod.service().resetPersistent(world);
        source.sendFeedback(
                () -> Text.literal("Chunk Copy persistent overlay reset in "
                        + world.getRegistryKey().getValue()),
                true
        );
        return 1;
    }
}
