package com.potatofps.threading;

import com.potatofps.PotatoFPS;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ThreadPoolManager - Manages the async chunk mesh building thread pool.
 *
 * WHY DEDICATED THREADS FOR CHUNK MESHING:
 *   Vanilla Minecraft's ChunkBuilder uses a shared thread pool. On the i5-3470S,
 *   this pool competes with game logic, rendering, and IO on just 4 cores.
 *   By creating a dedicated, isolated pool for mesh building with controlled thread
 *   count and priority, we guarantee chunk meshing never starves the render thread.
 *
 * THREAD COUNT STRATEGY FOR i5-3470S:
 *   - Core 0: Main thread (game loop, input, render calls)
 *   - Core 1: Render thread helpers (if any) + OS
 *   - Core 2: Chunk mesh worker #1
 *   - Core 3: Chunk mesh worker #2
 *   Default: 2 threads. Configurable 1-4.
 *   Setting >2 on a 4-core CPU causes context switching overhead that costs more
 *   than the parallelism gain.
 *
 * THREAD PRIORITY:
 *   Chunk workers run at Thread.NORM_PRIORITY - 1 (below normal).
 *   This ensures they yield to the main/render thread immediately when scheduled,
 *   preventing frame drops during intensive chunk loading phases.
 *
 * QUEUE STRATEGY:
 *   Uses a bounded ArrayBlockingQueue (not unbounded LinkedBlockingQueue).
 *   WHY BOUNDED: An unbounded queue can accumulate thousands of stale chunk mesh
 *   tasks during fast camera movement. Processing stale tasks wastes CPU and memory.
 *   The bounded queue drops oldest tasks when full, matching Sodium's approach.
 */
public final class ThreadPoolManager {

    private static ThreadPoolExecutor chunkMeshPool;
    private static final AtomicInteger threadCounter = new AtomicInteger(0);

    // Maximum pending chunk mesh tasks before oldest are discarded
    private static final int QUEUE_CAPACITY = 128;

    /**
     * Initialize the thread pool with the given worker count.
     * Safe to call multiple times — will shut down the old pool if it exists.
     *
     * @param workerCount Number of chunk mesh worker threads (1-8 recommended)
     */
    public static void initialize(int workerCount) {
        if (chunkMeshPool != null && !chunkMeshPool.isShutdown()) {
            PotatoFPS.LOGGER.info("[PotatoFPS] Shutting down existing chunk mesh pool...");
            shutdown();
        }

        PotatoFPS.LOGGER.info("[PotatoFPS] Starting chunk mesh pool with {} workers", workerCount);

        chunkMeshPool = new ThreadPoolExecutor(
                workerCount,                // core pool size
                workerCount,                // max pool size (fixed — no dynamic scaling)
                0L, TimeUnit.MILLISECONDS,  // keep-alive (irrelevant for fixed pool)
                new ArrayBlockingQueue<>(QUEUE_CAPACITY, true), // bounded, fair queue
                ThreadPoolManager::createWorkerThread,          // custom thread factory
                new DropOldestPolicy()      // when queue is full, drop oldest pending task
        );

        // Pre-start all worker threads so they're ready immediately when chunks load
        // (avoids the 50-100ms warmup delay on first chunk load after world join)
        chunkMeshPool.prestartAllCoreThreads();
    }

    /**
     * Submits a chunk meshing task to the worker pool.
     * Returns a Future that can be polled by the main thread for completion.
     *
     * @param task The chunk meshing Runnable (should be created from the pre-allocated
     *             ChunkMeshWorker pool to avoid GC pressure).
     * @return A Future for the submitted task, or null if the pool is not initialized.
     */
    public static Future<?> submitMeshTask(Runnable task) {
        if (chunkMeshPool == null || chunkMeshPool.isShutdown()) {
            PotatoFPS.LOGGER.warn("[PotatoFPS] Chunk mesh pool not initialized, running synchronously!");
            task.run();
            return null;
        }
        return chunkMeshPool.submit(task);
    }

    /**
     * Returns the number of pending tasks in the mesh queue.
     * Useful for the config screen's performance overlay.
     */
    public static int getPendingTaskCount() {
        return chunkMeshPool != null ? chunkMeshPool.getQueue().size() : 0;
    }

    /**
     * Returns the number of active (currently executing) mesh tasks.
     */
    public static int getActiveTaskCount() {
        return chunkMeshPool != null ? chunkMeshPool.getActiveCount() : 0;
    }

    /** Gracefully shuts down the thread pool, waiting up to 5 seconds for tasks to complete. */
    public static void shutdown() {
        if (chunkMeshPool == null) return;
        chunkMeshPool.shutdown();
        try {
            if (!chunkMeshPool.awaitTermination(5, TimeUnit.SECONDS)) {
                chunkMeshPool.shutdownNow();
                PotatoFPS.LOGGER.warn("[PotatoFPS] Chunk mesh pool did not terminate gracefully, forced shutdown.");
            }
        } catch (InterruptedException e) {
            chunkMeshPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        chunkMeshPool = null;
        threadCounter.set(0);
        PotatoFPS.LOGGER.info("[PotatoFPS] Chunk mesh pool shut down.");
    }

    /** Creates a named, daemon chunk worker thread with reduced priority. */
    private static Thread createWorkerThread(Runnable r) {
        Thread t = new Thread(r, "PotatoFPS-ChunkMesh-" + threadCounter.incrementAndGet());
        t.setDaemon(true); // Don't prevent JVM shutdown
        t.setPriority(Thread.NORM_PRIORITY - 1); // Yield to main thread
        return t;
    }

    /**
     * Custom RejectedExecutionHandler that drops the OLDEST task in the queue
     * instead of the newest (default AbortPolicy) or the caller's task (CallerRunsPolicy).
     *
     * WHY DROP OLDEST:
     *   When the camera moves quickly, old chunk mesh tasks become stale immediately.
     *   The newest task (most recently requested chunk near the player) is always
     *   more valuable than the oldest task (a chunk the player has moved away from).
     *   Dropping oldest = prioritizing what the player is looking at now.
     */
    private static class DropOldestPolicy implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            if (!executor.isShutdown()) {
                // Discard the head of the queue (oldest pending task)
                executor.getQueue().poll();
                // Now re-try submitting the new task
                executor.execute(r);
            }
        }
    }
}
