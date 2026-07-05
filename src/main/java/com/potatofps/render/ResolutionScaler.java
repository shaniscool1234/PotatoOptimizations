package com.potatofps.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.potatofps.PotatoFPS;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * ResolutionScaler - Manages the off-screen framebuffer used for render scaling.
 *
 * HOW IT WORKS:
 *   1. Before rendering the 3D world, bind this FBO and set the viewport to
 *      the SCALED resolution (e.g., 960×540 for 50% at 1080p).
 *   2. Minecraft renders the entire 3D scene into the scaled FBO.
 *   3. After the 3D pass, restore the main framebuffer and blit (upscale) the
 *      scaled texture to fill the window using the selected filter.
 *   4. The GUI/HUD render passes remain bound to the main framebuffer at full
 *      native resolution — text and UI elements stay sharp.
 *
 * WHY THIS IS THE #1 OPTIMIZATION FOR INTEGRATED GRAPHICS:
 *   Fill rate (pixels/second) is the primary bottleneck for Intel HD 2500.
 *   At 50% scale, the GPU processes 25% of the pixels (area scales quadratically).
 *   At 75% scale (our default), pixel work drops by 44% — easily worth the minor
 *   quality reduction, especially given Minecraft's low-res art style.
 *
 * OPENGL COMPATIBILITY:
 *   We use only FBO features available in OpenGL 3.0+. Intel HD 2500 supports
 *   OpenGL 4.0 on Windows, so this is safe. We avoid DSA (Direct State Access,
 *   OpenGL 4.5) which the HD 2500 driver may have bugs with.
 */
public class ResolutionScaler {

    // OpenGL object handles (-1 = not initialized)
    private static int fboId = -1;
    private static int colorTextureId = -1;
    private static int depthRenderbufferId = -1;

    // Current FBO dimensions (scaled)
    private static int fboWidth = -1;
    private static int fboHeight = -1;

    // Native window dimensions at last initialization
    private static int nativeWidth = -1;
    private static int nativeHeight = -1;

    /**
     * Must be called from the main render thread.
     * Ensures the FBO exists and matches the current window + scale settings.
     * Returns true if the scaler is active and the FBO should be used.
     */
    public static boolean ensureInitialized(int windowWidth, int windowHeight) {
        var config = PotatoFPS.CONFIG.getConfig();

        // If renderScale is 1.0, disable the scaler entirely (no quality loss)
        if (config.renderScale >= 0.99f) {
            cleanup();
            return false;
        }

        int targetW = Math.max(1, (int)(windowWidth  * config.renderScale));
        int targetH = Math.max(1, (int)(windowHeight * config.renderScale));

        // If dimensions haven't changed since last init, reuse existing FBO
        if (fboId != -1 && targetW == fboWidth && targetH == fboHeight) {
            return true;
        }

        // Window was resized or first initialization — recreate the FBO
        PotatoFPS.LOGGER.info("[PotatoFPS] ResolutionScaler: (re)creating FBO {}x{} (window {}x{}, scale {}%)",
                targetW, targetH, windowWidth, windowHeight, (int)(config.renderScale * 100));

        cleanup(); // Release any existing OpenGL objects first
        createFBO(targetW, targetH);

        nativeWidth  = windowWidth;
        nativeHeight = windowHeight;

        return fboId != -1; // Return false if creation failed
    }

    /**
     * Binds the scaled FBO. Call before starting the 3D world render pass.
     * Sets the OpenGL viewport to the scaled dimensions.
     */
    public static void bindScaledFBO() {
        if (fboId == -1) return;
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
        GL11.glViewport(0, 0, fboWidth, fboHeight);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
    }

    /**
     * Unbinds the scaled FBO and blits the scaled texture to the main framebuffer.
     * Call after the 3D world render pass, before the GUI pass.
     *
     * @param mainFboId The OpenGL FBO id of Minecraft's main framebuffer (usually 0 or Minecraft's FBO).
     */
    public static void blitToMain(int mainFboId, int windowWidth, int windowHeight) {
        if (fboId == -1) return;

        var config = PotatoFPS.CONFIG.getConfig();

        // --- Option A: Fast path using glBlitFramebuffer (OpenGL 3.0+) ---
        // This is a single GPU-side operation: no fragment shader, no texture bind.
        // For NEAREST upscaling, this is as fast as it gets.
        if (config.upscaleFilter == com.potatofps.config.PotatoConfig.UpscaleFilter.NEAREST) {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fboId);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, mainFboId);
            GL30.glBlitFramebuffer(
                    0, 0, fboWidth, fboHeight,         // src rect (scaled FBO)
                    0, 0, windowWidth, windowHeight,    // dst rect (full window)
                    GL11.GL_COLOR_BUFFER_BIT,           // blit color only (depth not needed)
                    GL11.GL_NEAREST                     // nearest-neighbor filter
            );
        } else {
            // --- Option B: Bilinear via glBlitFramebuffer ---
            // GL_LINEAR flag makes the driver use bilinear filtering during the blit.
            // Still a single GPU-side pass. Much cheaper than a full-screen quad pass.
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fboId);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, mainFboId);
            GL30.glBlitFramebuffer(
                    0, 0, fboWidth, fboHeight,
                    0, 0, windowWidth, windowHeight,
                    GL11.GL_COLOR_BUFFER_BIT,
                    GL11.GL_LINEAR
            );
        }

        // Restore draw framebuffer to main
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, mainFboId);

        // Restore viewport to native resolution for GUI rendering
        GL11.glViewport(0, 0, windowWidth, windowHeight);
    }

    /**
     * Returns true if the scaled FBO is currently active.
     */
    public static boolean isActive() {
        return fboId != -1;
    }

    public static int getScaledWidth()  { return fboWidth; }
    public static int getScaledHeight() { return fboHeight; }

    /**
     * Creates the OpenGL FBO, color texture attachment, and depth renderbuffer.
     *
     * WHY A TEXTURE ATTACHMENT (not renderbuffer) FOR COLOR:
     *   We need to sample the color texture in glBlitFramebuffer. Renderbuffers
     *   cannot be filtered with GL_LINEAR during blit — textures can.
     *   The performance difference is negligible for a single full-screen blit.
     *
     * WHY A RENDERBUFFER (not texture) FOR DEPTH:
     *   Depth is never sampled after the world pass. Renderbuffers have better
     *   driver support for depth formats on Intel HD drivers.
     */
    private static void createFBO(int width, int height) {
        // 1. Create FBO
        fboId = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);

        // 2. Color texture attachment (RGB, no alpha needed for world rendering)
        colorTextureId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTextureId);
        GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D, 0,
                GL11.GL_RGB,             // Internal format: RGB8 — saves ~25% vs RGBA8
                width, height,
                0,
                GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE,
                (java.nio.ByteBuffer) null
        );
        // Set filter modes on the texture (used by the LINEAR blit path)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL30.glFramebufferTexture2D(
                GL30.GL_FRAMEBUFFER,
                GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D,
                colorTextureId, 0
        );

        // 3. Depth renderbuffer (24-bit depth is sufficient for Minecraft's z-range)
        depthRenderbufferId = GL30.glGenRenderbuffers();
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depthRenderbufferId);
        GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH_COMPONENT24, width, height);
        GL30.glFramebufferRenderbuffer(
                GL30.GL_FRAMEBUFFER,
                GL30.GL_DEPTH_ATTACHMENT,
                GL30.GL_RENDERBUFFER,
                depthRenderbufferId
        );

        // 4. Verify FBO completeness
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            PotatoFPS.LOGGER.error("[PotatoFPS] FBO creation failed! Status: 0x{}", Integer.toHexString(status));
            cleanup();
            return;
        }

        fboWidth  = width;
        fboHeight = height;

        // Restore default FBO
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0);

        PotatoFPS.LOGGER.info("[PotatoFPS] FBO created successfully: {}x{}", width, height);
    }

    /**
     * Releases all OpenGL objects. Safe to call even if not initialized.
     * Must be called on the main GL thread.
     */
    public static void cleanup() {
        if (colorTextureId != -1) {
            GL11.glDeleteTextures(colorTextureId);
            colorTextureId = -1;
        }
        if (depthRenderbufferId != -1) {
            GL30.glDeleteRenderbuffers(depthRenderbufferId);
            depthRenderbufferId = -1;
        }
        if (fboId != -1) {
            GL30.glDeleteFramebuffers(fboId);
            fboId = -1;
        }
        fboWidth = fboHeight = nativeWidth = nativeHeight = -1;
    }
}
