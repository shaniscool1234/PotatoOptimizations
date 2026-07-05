package com.potatofps.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.potatofps.PotatoFPS;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ConfigManager - Handles loading and saving PotatoConfig to/from JSON on disk.
 *
 * WHY GSON AND NOT TOML/YAML:
 * GSON is already bundled inside Minecraft's jar (used by Mojang's data-driven
 * systems). Using it costs zero extra bytes in the mod jar and has zero extra
 * class-loading overhead at startup — critical for keeping mod load time short
 * on slow DDR3 systems.
 *
 * CONFIG LOCATION: .minecraft/config/potatofps.json
 */
public class ConfigManager {

    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("potatofps.json");

    /**
     * Pretty-printing GSON instance. Pretty-printing is fine because config is only
     * read/written during load and save — never inside the game loop.
     */
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .create();

    private PotatoConfig config;

    private ConfigManager(PotatoConfig config) {
        this.config = config;
    }

    /**
     * Loads the config from disk. If the file does not exist or is corrupted,
     * a fresh default config is created and saved immediately.
     *
     * @return A fully initialized ConfigManager.
     */
    public static ConfigManager loadOrCreate() {
        if (Files.exists(CONFIG_PATH)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
                PotatoConfig loaded = GSON.fromJson(reader, PotatoConfig.class);
                if (loaded != null) {
                    PotatoFPS.LOGGER.info("[PotatoFPS] Config loaded from {}", CONFIG_PATH);
                    // Validate loaded values are within safe ranges
                    loaded = sanitize(loaded);
                    ConfigManager manager = new ConfigManager(loaded);
                    // Always re-save after load to pick up any new fields added in updates
                    manager.save();
                    return manager;
                }
            } catch (IOException | com.google.gson.JsonSyntaxException e) {
                PotatoFPS.LOGGER.warn("[PotatoFPS] Failed to parse config file, resetting to defaults: {}", e.getMessage());
            }
        }

        // No file or parse error → create defaults and save
        PotatoFPS.LOGGER.info("[PotatoFPS] No config found, creating defaults at {}", CONFIG_PATH);
        ConfigManager manager = new ConfigManager(new PotatoConfig());
        manager.save();
        return manager;
    }

    /**
     * Saves the current config to disk.
     * Should be called after any settings change from the GUI.
     */
    public void save() {
        try {
            // Ensure config directory exists (it always should for Fabric, but be safe)
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(config, writer);
            }
            PotatoFPS.LOGGER.info("[PotatoFPS] Config saved to {}", CONFIG_PATH);
        } catch (IOException e) {
            PotatoFPS.LOGGER.error("[PotatoFPS] Failed to save config: {}", e.getMessage());
        }
    }

    /**
     * Resets the config to factory defaults and saves.
     * Exposed to the GUI's "Reset to Defaults" button.
     */
    public void resetToDefaults() {
        config = new PotatoConfig();
        save();
        PotatoFPS.LOGGER.info("[PotatoFPS] Config reset to defaults.");
    }

    public PotatoConfig getConfig() {
        return config;
    }

    /**
     * Validates and clamps all config values to safe ranges.
     * Prevents invalid values from disk (e.g., renderScale=99.0) crashing the renderer.
     *
     * This is critical for robustness: corrupted or hand-edited configs should degrade
     * gracefully, not cause an OutOfMemoryError allocating a 9000×9000 framebuffer.
     */
    private static PotatoConfig sanitize(PotatoConfig cfg) {
        // Clamp render scale to 25%-100%
        cfg.renderScale = Math.max(0.25f, Math.min(1.0f, cfg.renderScale));

        // Clamp thread count to 1-8
        cfg.chunkBuilderThreads = Math.max(1, Math.min(8, cfg.chunkBuilderThreads));

        // Clamp AI throttle distance to 8-128 blocks
        cfg.entityAiThrottleDistance = Math.max(8, Math.min(128, cfg.entityAiThrottleDistance));

        // Clamp entity cull distance to 16-256 blocks
        cfg.entityCullDistance = Math.max(16, Math.min(256, cfg.entityCullDistance));

        // Clamp particle multiplier to 0%-100%
        cfg.particleMultiplier = Math.max(0.0f, Math.min(1.0f, cfg.particleMultiplier));

        // Clamp object pool size to 64-1024
        cfg.objectPoolSize = Math.max(64, Math.min(1024, cfg.objectPoolSize));

        // Null-guard enums (GSON may deserialize unknown values as null)
        if (cfg.upscaleFilter == null) cfg.upscaleFilter = PotatoConfig.UpscaleFilter.BILINEAR;
        if (cfg.animationTickRate == null) cfg.animationTickRate = PotatoConfig.AnimationRate.HALF;

        return cfg;
    }
}
