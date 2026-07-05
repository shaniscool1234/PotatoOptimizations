package com.potatofps.memory;

import com.potatofps.PotatoFPS;
import com.potatofps.threading.ChunkMeshWorker;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * ObjectPool - Pre-allocated pools for frequently created render objects.
 *
 * WHY OBJECT POOLING MATTERS ON 8GB DDR3 SYSTEMS:
 *   Minecraft's JVM is typically allocated 2-4GB. The remaining 4-6GB is shared
 *   between the OS, Intel HD Graphics driver, and other applications. On DDR3,
 *   every GC pause stops all application threads. Even a 50ms GC pause at an
 *   unlucky moment drops an entire frame (at 60 FPS, one frame = 16.7ms).
 *
 *   Key allocation sites we pool:
 *   - ChunkMeshWorker: ~500KB each, created 50+ times per second during chunk loading
 *   - float[9] scratch arrays: created during vertex decompression (millions/sec without pooling)
 *   - int[27] neighbor caches: created during smooth lighting (1 per visible face)
 *
 * POOL IMPLEMENTATION CHOICE:
 *   Using ArrayDeque as the backing store (not ConcurrentLinkedQueue).
 *   WHY: ChunkMeshWorker pools are accessed from: main thread (acquire/release).
 *        The pool itself is not concurrent — workers are submitted to a thread pool
 *        but the pool acquire/release happens on the main thread only.
 *   If thread-safe pool access is ever needed, add synchronized blocks or use
 *   ConcurrentLinkedDeque. For now, single-thread access is faster.
 *
 * POOL SIZE:
 *   Configurable via PotatoConfig.objectPoolSize. Default 256.
 *   Too small = pool exhausted, falls back to new allocations (GC pressure returns).
 *   Too large = wasted heap space. 256 ChunkMeshWorkers × 500KB ≈ 128MB — acceptable.
 */
public final class ObjectPool {

    // =========================================================================
    // CHUNK MESH WORKER POOL
    // =========================================================================

    private static final Deque<ChunkMeshWorker> workerPool = new ArrayDeque<>();
    private static int maxWorkers = 64; // Updated from config during preWarm()

    /**
     * Acquires a ChunkMeshWorker from the pool.
     * If the pool is empty, creates a new one (fallback — will cause GC eventually).
     *
     * @return A ready-to-use ChunkMeshWorker with inUse=true
     */
    public static ChunkMeshWorker acquireWorker() {
        ChunkMeshWorker worker = workerPool.pollFirst();
        if (worker == null) {
            // Pool exhausted — create a new one
            // This is the fallback path; should be rare if pool is properly sized
            PotatoFPS.LOGGER.debug("[PotatoFPS] ObjectPool: Worker pool exhausted, allocating new worker");
            worker = new ChunkMeshWorker();
        }
        worker.inUse = true;
        return worker;
    }

    /**
     * Returns a used ChunkMeshWorker to the pool for reuse.
     * Always call this after the main thread is done with a worker's output.
     *
     * @param worker The worker to return. Must not be submitted to the thread pool again
     *               until after the NEXT acquireWorker() + setup cycle.
     */
    public static void releaseWorker(ChunkMeshWorker worker) {
        if (worker == null) return;
        worker.inUse = false;
        worker.builtVertexBytes = 0;
        worker.builtVertexCount = 0;
        // Only return to pool if below max capacity
        if (workerPool.size() < maxWorkers) {
            workerPool.addFirst(worker); // LIFO: recently used workers are "warm" in CPU cache
        }
        // If above max, let the worker be GC'd (infrequent enough to be acceptable)
    }

    // =========================================================================
    // FLOAT ARRAY POOL (for scratch vertex buffers)
    // =========================================================================

    private static final Deque<float[]> float9Pool = new ArrayDeque<>();
    private static final int MAX_FLOAT9 = 512;

    /**
     * Acquires a float[9] scratch array. Used for vertex decompression results
     * to avoid allocating a new float[] on every vertex read.
     */
    public static float[] acquireFloat9() {
        float[] arr = float9Pool.pollFirst();
        return arr != null ? arr : new float[9];
    }

    public static void releaseFloat9(float[] arr) {
        if (arr != null && arr.length == 9 && float9Pool.size() < MAX_FLOAT9) {
            float9Pool.addFirst(arr);
        }
    }

    // =========================================================================
    // INT ARRAY POOL (for neighbor light caches)
    // =========================================================================

    private static final Deque<int[]> int27Pool = new ArrayDeque<>();
    private static final int MAX_INT27 = 512;

    /** Acquires an int[27] array for the 3×3×3 neighbor light cache. */
    public static int[] acquireInt27() {
        int[] arr = int27Pool.pollFirst();
        return arr != null ? arr : new int[27];
    }

    public static void releaseInt27(int[] arr) {
        if (arr != null && arr.length == 27 && int27Pool.size() < MAX_INT27) {
            int27Pool.addFirst(arr);
        }
    }

    // =========================================================================
    // LIFECYCLE
    // =========================================================================

    /**
     * Pre-allocates pool objects to avoid GC spikes during initial world load.
     * Called during mod initialization, before the player enters a world.
     *
     * WHY PRE-WARM:
     *   The first ~30 seconds after world load are the most chunk-intensive
     *   (all surrounding chunks need meshing). Without pre-warming, the pool starts
     *   empty and creates all objects via new(), which fills the young generation heap
     *   and triggers a full GC right when performance matters most.
     */
    public static void preWarm() {
        var config = PotatoFPS.CONFIG.getConfig();
        maxWorkers = Math.min(config.objectPoolSize / 4, 64); // Reserve 1/4 pool space for workers

        int workerCount = Math.min(config.chunkBuilderThreads * 8, 32);
        PotatoFPS.LOGGER.info("[PotatoFPS] Pre-warming object pool: {} workers, {} float9, {} int27",
                workerCount, 128, 128);

        for (int i = 0; i < workerCount; i++) {
            workerPool.addFirst(new ChunkMeshWorker());
        }
        for (int i = 0; i < 128; i++) {
            float9Pool.addFirst(new float[9]);
        }
        for (int i = 0; i < 128; i++) {
            int27Pool.addFirst(new int[27]);
        }

        PotatoFPS.LOGGER.info("[PotatoFPS] Object pool pre-warmed. Heap impact: ~{}MB",
                (workerPool.size() * 500 + float9Pool.size() / 1024 + int27Pool.size() / 1024));
    }

    /**
     * Clears all pools and releases all held objects to the GC.
     * Called on world unload / client shutdown.
     */
    public static void clear() {
        int workerCount = workerPool.size();
        workerPool.clear();
        float9Pool.clear();
        int27Pool.clear();
        PotatoFPS.LOGGER.info("[PotatoFPS] Object pool cleared ({} workers released).", workerCount);
    }

    /** Returns pool occupancy stats for the debug overlay. */
    public static String getStats() {
        return String.format("Pool[workers=%d, f9=%d, i27=%d]",
                workerPool.size(), float9Pool.size(), int27Pool.size());
    }
}
