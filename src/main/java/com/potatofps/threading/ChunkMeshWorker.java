package com.potatofps.threading;

import com.potatofps.PotatoFPS;
import com.potatofps.memory.ObjectPool;
import com.potatofps.render.SmoothLightingOptimizer;
import com.potatofps.render.VertexCompressor;

/**
 * ChunkMeshWorker - A reusable worker task for async chunk mesh building.
 *
 * HOW IT FITS INTO THE PIPELINE:
 *   1. Main thread detects a chunk needs remeshing (block placed, light update, etc.)
 *   2. Main thread acquires a ChunkMeshWorker from the ObjectPool (zero allocation)
 *   3. Main thread snapshots the chunk data into the worker's input buffers
 *      (snapshot is needed because chunk data can change while the worker runs)
 *   4. Main thread submits the worker to ThreadPoolManager
 *   5. Worker thread builds the compressed vertex buffer
 *   6. Main thread polls for completion and uploads the vertex buffer to the GPU
 *   7. Main thread returns the worker to the ObjectPool
 *
 * WHY OBJECT POOLING HERE:
 *   Without pooling, each chunk remesh event would allocate a new ChunkMeshWorker
 *   with its ~500KB internal buffers. At 60 FPS with 50 chunk updates/second,
 *   that's 50 × 500KB = 25MB of allocations per second, causing GC every ~1 second
 *   on an 8GB DDR3 system with 2GB heap. Pooling reduces this to zero.
 *
 * THREAD SAFETY:
 *   Input data (chunkSnapshot) is written by the main thread BEFORE submission.
 *   Output data (builtVertices) is written by the worker thread DURING execution.
 *   Main thread reads output AFTER completion (Future.get() provides happens-before).
 *   No locks needed due to the sequential access pattern.
 */
public final class ChunkMeshWorker implements Runnable {

    // =========================================================================
    // INPUT (set by main thread before submission)
    // =========================================================================

    /** Chunk section local X, Y, Z origin in world coordinates. */
    public int chunkX, chunkY, chunkZ;

    /**
     * Snapshot of block IDs for a 18×18×18 region (16×16×16 section + 1 block border).
     * The border is needed for AO/lighting calculations at section edges.
     * Size: 18×18×18 = 5832 ints.
     *
     * WHY int[] NOT Block[]:
     *   Block objects would require 5832 object references + 5832 Block instances.
     *   Using numerical IDs eliminates all object overhead. ID → Block lookup
     *   only happens when needed (typically for ~30% of blocks: non-air).
     */
    public final int[] blockSnapshot = new int[18 * 18 * 18];

    /**
     * Snapshot of block light levels for the same 18×18×18 region.
     * Packed: bits 0-3 = block light, bits 4-7 = sky light.
     */
    public final int[] lightSnapshot = new int[18 * 18 * 18];

    // =========================================================================
    // OUTPUT (written by worker thread, read by main thread after completion)
    // =========================================================================

    /**
     * Compressed vertex buffer produced by this worker.
     * Contains all visible faces of the chunk section in compressed vertex format.
     * Sized for worst case: 16×16×16 = 4096 blocks × 6 faces × 4 vertices × 14 bytes.
     *
     * In practice, typical usage is 5-15% of maximum capacity.
     * We pre-allocate maximum capacity to avoid reallocation during building.
     */
    public final byte[] builtVertices = new byte[4096 * 6 * 4 * 14];

    /** Number of valid bytes written into builtVertices. */
    public int builtVertexBytes = 0;

    /** Number of vertices written (builtVertexBytes / 14). */
    public int builtVertexCount = 0;

    /** True if this worker has been submitted and not yet returned to the pool. */
    public volatile boolean inUse = false;

    // =========================================================================
    // WORKER INTERNALS
    // =========================================================================

    /** Per-worker smooth lighting optimizer (no synchronization needed — one per worker). */
    private final SmoothLightingOptimizer lightOptimizer = new SmoothLightingOptimizer();

    /** Reusable float[] for vertex decompression result (zero allocation). */
    private final float[] scratchVertex = new float[9];

    @Override
    public void run() {
        try {
            buildMesh();
        } catch (Exception e) {
            PotatoFPS.LOGGER.error("[PotatoFPS] ChunkMeshWorker exception at chunk ({},{},{}): {}",
                    chunkX, chunkY, chunkZ, e.getMessage(), e);
            builtVertexBytes = 0;
            builtVertexCount = 0;
        }
    }

    /**
     * Core mesh building algorithm.
     *
     * ALGORITHM OVERVIEW:
     *   For each of the 16×16×16 blocks in the section:
     *   1. Skip air blocks (fast path, ~70% of blocks in typical worlds)
     *   2. For each of 6 faces: check if the adjacent block is transparent
     *   3. If visible: cache 27-neighbor lighting, compute face lighting, write compressed vertices
     *
     * CACHE-FRIENDLINESS:
     *   Iterating Y→Z→X (innermost X) matches Minecraft's block storage layout
     *   (PalettedContainer uses XZY order). This maximizes L1 cache hits during
     *   the blockSnapshot[] access pattern.
     */
    private void buildMesh() {
        builtVertexBytes = 0;
        builtVertexCount = 0;

        // Iterate the 16×16×16 section
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    // Access the block at (x, y, z) in the snapshot.
                    // Snapshot includes 1-block border, so (x+1, y+1, z+1) maps to section (x, y, z)
                    int blockId = blockSnapshot[snapshotIdx(x + 1, y + 1, z + 1)];

                    // AIR fast path: ~70% of blocks are air, skip immediately
                    if (blockId == 0) continue;

                    // Cache the 27-neighbor lighting for this block position
                    // We capture x, y, z in the lambda context (effectively final)
                    final int finalX = x, finalY = y, finalZ = z;
                    lightOptimizer.cacheNeighborhood(
                            // LightGetter: returns packed light at offset (dx, dy, dz)
                            (dx, dy, dz) -> lightSnapshot[snapshotIdx(finalX + 1 + dx, finalY + 1 + dy, finalZ + 1 + dz)],
                            // OpaqueChecker: returns true if neighbor is a full opaque block
                            (dx, dy, dz) -> isOpaque(blockSnapshot[snapshotIdx(finalX + 1 + dx, finalY + 1 + dy, finalZ + 1 + dz)])
                    );

                    // Check and emit each of 6 faces
                    for (int face = 0; face < 6; face++) {
                        // Neighbor block in the direction of this face
                        int[] dir = FACE_DIRECTIONS[face];
                        int neighborId = blockSnapshot[snapshotIdx(x + 1 + dir[0], y + 1 + dir[1], z + 1 + dir[2])];

                        // Only render face if neighbor is transparent (air or translucent)
                        if (!isTransparent(neighborId)) continue;

                        // Compute smooth lighting for this face
                        int[] cornerLight = lightOptimizer.computeFaceLighting(face);

                        // Emit 4 vertices for this face (2 triangles = 1 quad)
                        emitFaceVertices(x, y, z, face, blockId, cornerLight);
                    }
                }
            }
        }
    }

    /**
     * Emits 4 compressed vertices for a single block face.
     *
     * The 4 vertices form a quad which will be rendered as 2 triangles
     * by the index buffer (0,1,2 and 0,2,3 — shared by all quads).
     * Using index buffers instead of duplicating vertices saves 33% vertex data.
     */
    private void emitFaceVertices(int bx, int by, int bz, int face, int blockId,
                                   int[] cornerLight) {
        // Get the 4 corner positions for this face at this block position
        float[][] corners = FACE_CORNERS[face];

        for (int i = 0; i < 4; i++) {
            float[] c = corners[i];

            // World position: block origin + corner offset
            float wx = bx + c[0];
            float wy = by + c[1];
            float wz = bz + c[2];

            // Texture UV: simplified placeholder (real impl would use block texture atlas UVs)
            float u = FACE_UVS[i][0];
            float v = FACE_UVS[i][1];

            // Lighting from smooth lighting optimizer
            int blockLight = cornerLight[i * 3];
            int skyLight   = cornerLight[i * 3 + 1];
            int aoFactor   = cornerLight[i * 3 + 2]; // 0-255

            // Compute final color with AO applied
            // Block color simplified to white (real impl multiplies by block tint)
            int r = aoFactor;
            int g = aoFactor;
            int b = aoFactor;
            int a = 255;

            // Write compressed vertex
            VertexCompressor.writeCompressedVertex(
                    builtVertices, builtVertexBytes,
                    wx, wy, wz,
                    r, g, b, a,
                    u, v,
                    (blockLight & 0xF) | ((skyLight & 0xF) << 4)
            );

            builtVertexBytes += 14; // Each compressed vertex is 14 bytes
            builtVertexCount++;
        }
    }

    // =========================================================================
    // DATA TABLES
    // =========================================================================

    /** Face direction offsets: DOWN, UP, NORTH, SOUTH, WEST, EAST */
    private static final int[][] FACE_DIRECTIONS = {
        { 0, -1,  0}, // DOWN
        { 0,  1,  0}, // UP
        { 0,  0, -1}, // NORTH
        { 0,  0,  1}, // SOUTH
        {-1,  0,  0}, // WEST
        { 1,  0,  0}  // EAST
    };

    /** Corner offsets for each face (x, y, z offsets from block origin) */
    private static final float[][][] FACE_CORNERS = {
        // DOWN (y-1)
        {{0,0,0},{1,0,0},{1,0,1},{0,0,1}},
        // UP (y+1)
        {{0,1,0},{0,1,1},{1,1,1},{1,1,0}},
        // NORTH (z-1)
        {{1,0,0},{0,0,0},{0,1,0},{1,1,0}},
        // SOUTH (z+1)
        {{0,0,1},{1,0,1},{1,1,1},{0,1,1}},
        // WEST (x-1)
        {{0,0,1},{0,0,0},{0,1,0},{0,1,1}},
        // EAST (x+1)
        {{1,0,0},{1,0,1},{1,1,1},{1,1,0}}
    };

    /** UV coordinates for the 4 corners of each face */
    private static final float[][] FACE_UVS = {
        {0.0f, 0.0f},
        {1.0f, 0.0f},
        {1.0f, 1.0f},
        {0.0f, 1.0f}
    };

    /** Snapshot array index for 18×18×18 region. Offset by 1 for the border. */
    private static int snapshotIdx(int x, int y, int z) {
        return y * 18 * 18 + z * 18 + x;
    }

    /** Returns true if the block ID represents an air or transparent block. */
    private static boolean isTransparent(int blockId) {
        return blockId == 0; // 0 = air. Simplified; real impl checks block.getRenderType()
    }

    /** Returns true if the block ID is a full opaque cube (contributes to AO). */
    private static boolean isOpaque(int blockId) {
        return blockId != 0; // Simplified; real impl checks BlockState.isOpaque()
    }
}
