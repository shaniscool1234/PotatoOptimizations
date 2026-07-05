package com.potatofps;

import com.potatofps.config.ConfigManager;
import com.potatofps.render.ResolutionScaler;
import com.potatofps.threading.ThreadPoolManager;
import com.potatofps.memory.ObjectPool;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PotatoFPS - Main entry point.
 *
 * DESIGN PHILOSOPHY:
 * Intel HD Graphics 2500 has ~5.5 GB/s memory bandwidth (shared with CPU over DDR3).
 * Every optimization here targets one of three bottlenecks:
 *   1. Memory bandwidth  - vertex compression, lower res render target
 *   2. Draw call count   - geometry batching, aggressive culling
 *   3. GC pressure       - object pooling, primitive arrays
 *
 * All features are independently toggleable so users can find the best
 * combination for their specific hardware.
 */
public class PotatoFPS implements ClientModInitializer {

    public static final String MOD_ID = "potatofps";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Singleton config accessible from anywhere (thread-safe reads after init). */
    public static ConfigManager CONFIG;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[PotatoFPS] Initializing - aggressively optimizing for legacy hardware...");

        // 1. Load or create config from disk (JSON, GSON-backed)
        CONFIG = ConfigManager.loadOrCreate();
        LOGGER.info("[PotatoFPS] Config loaded: render scale={}%, threads={}",
                (int)(CONFIG.getConfig().renderScale * 100),
                CONFIG.getConfig().chunkBuilderThreads);

        // 2. Start the async chunk meshing thread pool ASAP.
        //    The pool size defaults to 2 for the i5-3470S (4 cores):
        //    - 1 core for main thread game logic
        //    - 1 core for the OS / background
        //    - 2 cores dedicated to chunk mesh building
        ThreadPoolManager.initialize(CONFIG.getConfig().chunkBuilderThreads);

        // 3. Pre-warm object pools to avoid GC spikes on first load
        ObjectPool.preWarm();

        // 4. Register lifecycle hooks
        registerLifecycleEvents();

        LOGGER.info("[PotatoFPS] Initialization complete. Potato mode: ENGAGED.");
    }

    private void registerLifecycleEvents() {
        // On world unload: aggressively free memory if the option is enabled.
        // Intel HD 2500 uses shared DDR3 RAM. Leftover Minecraft allocations
        // directly compress the VRAM budget, causing texture thrashing.
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            LOGGER.info("[PotatoFPS] Client stopping - cleaning up resources...");
            ThreadPoolManager.shutdown();
            ResolutionScaler.cleanup();
            ObjectPool.clear();
        });

        // Tick-based animation throttling:
        // Water/lava/fire texture animations are CPU-expensive. If an entity
        // is >16 blocks away, the player cannot notice 1/4-speed animation.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.world != null) {
                AnimationThrottler.tick(client);
            }
        });
    }
}
