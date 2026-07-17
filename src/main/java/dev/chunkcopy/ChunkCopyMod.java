package dev.chunkcopy;

import dev.chunkcopy.config.ChunkCopyConfig;
import dev.chunkcopy.command.ChunkCopyCommands;
import dev.chunkcopy.replication.ChunkCopyService;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ChunkCopyMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger(ChunkCopyIds.MOD_ID);

    private static ChunkCopyConfig config;
    private static ChunkCopyService service;

    @Override
    public void onInitialize() {
        config = ChunkCopyConfig.load();
        service = new ChunkCopyService(config, LOGGER);

        ServerChunkEvents.CHUNK_LOAD.register(service::onChunkLoad);
        ServerChunkEvents.CHUNK_UNLOAD.register(service::onChunkUnload);
        ServerChunkEvents.CHUNK_LEVEL_TYPE_CHANGE.register(service::onChunkLevelChange);
        ServerTickEvents.END_SERVER_TICK.register(service::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> service.clearRuntimeState());
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                ChunkCopyCommands.register(dispatcher));

        LOGGER.info("Chunk Copy initialized (default enabled: {}, default mode: {})",
                config.defaultEnabled,
                config.defaultMode);
    }

    public static boolean isReady() {
        return service != null;
    }

    public static ChunkCopyConfig config() {
        if (config == null) {
            throw new IllegalStateException("Chunk Copy has not initialized");
        }
        return config;
    }

    public static ChunkCopyService service() {
        if (service == null) {
            throw new IllegalStateException("Chunk Copy has not initialized");
        }
        return service;
    }
}
