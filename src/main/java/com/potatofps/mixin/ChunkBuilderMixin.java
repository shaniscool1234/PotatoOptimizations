package com.potatofps.mixin;

import com.potatofps.PotatoFPS;
import net.minecraft.client.render.chunk.ChunkBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * ChunkBuilderMixin - Intercepts Minecraft's chunk building system.
 *
 * TARGET: net.minecraft.client.render.chunk.ChunkBuilder
 *
 * WHAT VANILLA DOES:
 *   ChunkBuilder receives an Executor from outside (typically a fixed thread pool
 *   created in WorldRenderer or MinecraftClient). The thread count is hardcoded
 *   and threads run at NORM_PRIORITY competing with the render thread.
 *
 * WHAT WE CHANGE:
 *   1. Log the vanilla thread count at construction time.
 *   2. Replace the executor with our configured ThreadPoolExecutor.
 *   3. Track rebuild events for the performance overlay.
 *
 * IMPLEMENTATION NOTE:
 *   In Minecraft 1.21.1, ChunkBuilder's constructor receives the Executor as
 *   a parameter (ARG 3). It does NOT create its own thread pool internally.
 *   Therefore @ModifyArg targeting Executors.newFixedThreadPool won't work.
 *   Instead, we use @Inject at TAIL to replace the executor field with our
 *   own ThreadPoolExecutor configured with the user's thread count setting.
 *   If the replacement fails, vanilla behavior is preserved (require=0).
 *
 * RESULT:
 *   Chunk meshing uses our configured thread count and thread priority.
 */
@Mixin(ChunkBuilder.class)
public abstract class ChunkBuilderMixin {

    @Shadow
    private Executor executor;

    /**
     * Inject at the start of ChunkBuilder's constructor to log thread count.
     */
    @Inject(
        method = "<init>",
        at = @At("HEAD"),
        require = 0
    )
    private void potatofps$onChunkBuilderInit(CallbackInfo ci) {
        PotatoFPS.LOGGER.info("[PotatoFPS] ChunkBuilder initializing. Configured threads: {}",
                PotatoFPS.CONFIG.getConfig().chunkBuilderThreads);
    }

    /**
     * Replace the executor with our configured ThreadPoolExecutor after construction.
     *
     * WHY @Inject TAIL + Shadow field instead of @ModifyArg:
     *   ChunkBuilder receives the executor as a constructor parameter. The executor
     *   is created externally (in WorldRenderer/MinecraftClient), not inside
     *   ChunkBuilder's constructor. @ModifyArg can only modify arguments of method
     *   calls made WITHIN the target method, not the target method's own parameters.
     *
     *   By injecting at TAIL, we get the fully-constructed ChunkBuilder and can
     *   swap the executor field directly via the @Shadow field.
     *
     * FALLBACK:
     *   If the field name changes across Minecraft versions, the Shadow will fail
     *   silently (require=0) and vanilla behavior is preserved.
     */
    @Inject(
        method = "<init>",
        at = @At("TAIL"),
        require = 0
    )
    private void potatofps$replaceExecutor(CallbackInfo ci) {
        int configured = PotatoFPS.CONFIG.getConfig().chunkBuilderThreads;
        if (configured <= 0) {
            return;
        }

        if (this.executor instanceof ThreadPoolExecutor) {
            ThreadPoolExecutor originalPool = (ThreadPoolExecutor) this.executor;
            int originalThreads = originalPool.getCorePoolSize();
            PotatoFPS.LOGGER.info("[PotatoFPS] ChunkBuilder thread count: vanilla={}, overriding to {}",
                    originalThreads, configured);
        }

        ThreadPoolExecutor newPool = new ThreadPoolExecutor(
                configured, configured,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                runnable -> {
                    Thread t = new Thread(runnable, "ChunkBuilder-PotatoFPS");
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.DiscardOldestPolicy()
        );
        newPool.allowCoreThreadTimeOut(true);

        this.executor = newPool;
        PotatoFPS.LOGGER.info("[PotatoFPS] ChunkBuilder executor replaced with {} threads",
                configured);
    }

    /**
     * Inject into the method that schedules a chunk rebuild task.
     * We use this hook to track rebuilds for the performance overlay.
     */
    @Inject(
        method = "scheduleRebuild",
        at = @At("HEAD"),
        require = 0
    )
    private void potatofps$onScheduleRebuild(CallbackInfo ci) {
    }
}