package dev.chunkcopy.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Server configuration. World-specific enabled/mode values are persisted separately; this file
 * supplies defaults for worlds that have not created state yet.
 */
public final class ChunkCopyConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "chunkcopy.json";

    public int schemaVersion = 1;
    public boolean defaultEnabled = true;
    public String defaultMode = "loaded";
    public int maxCapturedPositionsPerAction = 4_096;
    public int maxBlockWritesPerTick = 4_096;
    public int maxQueuedBlockWrites = 262_144;
    public int maxSpawnerClonesPerTick = 256;
    public int maxQueuedEntityClones = 32_768;
    public boolean mirrorMonsterSpawnerSpawns = true;
    public boolean logQueueOverflows = true;
    public boolean logSkippedProtectedBlocks = false;

    public static ChunkCopyConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        if (Files.notExists(path)) {
            ChunkCopyConfig defaults = new ChunkCopyConfig();
            defaults.validate();
            write(path, defaults);
            return defaults;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            ChunkCopyConfig config = GSON.fromJson(reader, ChunkCopyConfig.class);
            if (config == null) {
                throw new JsonParseException("configuration document is empty");
            }
            config.validate();
            return config;
        } catch (IOException | JsonParseException | IllegalArgumentException exception) {
            throw new IllegalStateException("Unable to load " + path + ": " + exception.getMessage(), exception);
        }
    }

    private static void write(Path path, ChunkCopyConfig config) {
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(config, writer);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to write default configuration to " + path, exception);
        }
    }

    private void validate() {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("unsupported schemaVersion " + schemaVersion);
        }
        if (!defaultMode.equalsIgnoreCase("loaded") && !defaultMode.equalsIgnoreCase("persistent")) {
            throw new IllegalArgumentException("defaultMode must be loaded or persistent");
        }
        requirePositive("maxCapturedPositionsPerAction", maxCapturedPositionsPerAction);
        requirePositive("maxBlockWritesPerTick", maxBlockWritesPerTick);
        requirePositive("maxQueuedBlockWrites", maxQueuedBlockWrites);
        requirePositive("maxSpawnerClonesPerTick", maxSpawnerClonesPerTick);
        requirePositive("maxQueuedEntityClones", maxQueuedEntityClones);
    }

    private static void requirePositive(String name, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
    }
}
