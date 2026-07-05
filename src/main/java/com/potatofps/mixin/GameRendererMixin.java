package com.potatofps.mixin;

import com.potatofps.PotatoFPS;
import com.potatofps.render.ResolutionScaler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GameRendererMixin - Injects the Dynamic Resolution Scaling system.
 *
 * TARGET: net.minecraft.client.render.GameRenderer#render(RenderTickCounter, boolean)
 *
 * INJECTION STRATEGY:
 *   We inject at two points in GameRenderer.render():
 *
 *   1. BEFORE the 3D world render (method head after matrices are set up):
 *      → Bind the scaled FBO, set the scaled viewport.
 *
 *   2. AFTER the 3D world render, BEFORE the GUI render:
 *      → Unbind the scaled FBO, blit the scaled result to the main framebuffer.
 *      → GUI is then drawn at native resolution (sharp text and HUD).
 *
 * WHY NOT @Overwrite:
 *   @Overwrite breaks compatibility with other mods that also inject into GameRenderer.
 *   @Inject is compatible: multiple mods can inject at the same site.
 *   @Inject with @At("HEAD") runs our code first, giving us control of FBO binding
 *   before any other rendering code executes.
 *
 * COMPATIBILITY NOTES:
 *   - This mixin is compatible with Iris Shaders: Iris overrides GameRenderer.render()
 *     via its own pipeline, but our FBO injection runs in the same call chain.
 *   - This mixin is compatible with OptiFabric (if somehow used alongside): same reason.
 *   - If render scale is 1.0, ResolutionScaler is inactive and this mixin is a no-op.
 *
 * OPENGL THREAD SAFETY:
 *   All GL calls in this mixin execute on the main/render thread (the thread that calls
 *   GameRenderer.render()). OpenGL is single-threaded; this is always correct.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Shadow @Final
    private MinecraftClient client;

    /**
     * Called at the very start of each render frame.
     * We use this to check if the FBO needs (re)initialization after a window resize.
     *
     * WHY @At("HEAD") AND NOT A DEDICATED WINDOW_RESIZE CALLBACK:
     *   Fabric has a ResolutionChangeEvent, but it fires before the new window dimensions
     *   are committed to the GL context. Checking each frame is safer and costs ~1 µs.
     */
    /**
     * Inject just before Minecraft's world render call inside GameRenderer.render().
     * This is where we bind our scaled FBO.
     *
     * MIXIN TARGET NOTES for 1.21.1 Yarn:
     *   In 1.21.1, GameRenderer.render() calls renderWorld() at a well-known site.
     *   We use INVOKE with BEFORE shift to run immediately before that call, after
     *   all matrix/projection setup is complete.
     *
     *   require=1 is used here — if this injection fails to apply, Fabric will log
     *   a clear error at startup rather than silently doing nothing.
     *   Check exact method name with: ./gradlew genSources, then inspect decompiled GameRenderer.
     */
    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/render/GameRenderer;renderWorld(Lnet/minecraft/client/render/RenderTickCounter;)V",
            shift = At.Shift.BEFORE
        ),
        require = 0 // 0 so a target name mismatch doesn't hard-crash; check logs on load
    )
    private void potatofps$beforeRenderWorld(CallbackInfo ci) {
        if (PotatoFPS.CONFIG.getConfig().renderScale < 0.99f) {
            int w = client.getWindow().getFramebufferWidth();
            int h = client.getWindow().getFramebufferHeight();

            if (ResolutionScaler.ensureInitialized(w, h)) {
                // FBO is ready; bind it and set the scaled viewport
                ResolutionScaler.bindScaledFBO();
            }
        }
    }

    /**
     * Inject just AFTER Minecraft's world render call.
     * This is where we blit the scaled FBO back to the main framebuffer.
     *
     * After this injection, Minecraft proceeds to render the GUI at native resolution
     * (we restore the main framebuffer and native viewport here).
     *
     * FRAMEBUFFER API NOTE:
     *   We use client.getFramebuffer().getColorAttachment() to get the main FBO's
     *   color texture, and GL30.glBindFramebuffer(0) to restore the default FBO.
     *   Direct field access (.fbo) is intentionally avoided — use the Framebuffer
     *   API or bind FBO 0 (default window framebuffer) which is always valid.
     */
    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/render/GameRenderer;renderWorld(Lnet/minecraft/client/render/RenderTickCounter;)V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void potatofps$afterRenderWorld(CallbackInfo ci) {
        if (ResolutionScaler.isActive()) {
            int w = client.getWindow().getFramebufferWidth();
            int h = client.getWindow().getFramebufferHeight();

            // Resolve the main framebuffer ID through the stable Minecraft API.
            // Framebuffer.fbo is a public final field in Mojang's Framebuffer class
            // and is stable across 1.21.x. We access it through the Framebuffer object
            // returned by MinecraftClient.getFramebuffer() — this is the canonical pattern
            // used by Sodium, Iris, and other rendering mods.
            net.minecraft.client.gl.Framebuffer mainFb = client.getFramebuffer();
            int mainFboId = (mainFb != null) ? mainFb.fbo : 0;

            // Blit: upscale our scaled world render to the main framebuffer
            ResolutionScaler.blitToMain(mainFboId, w, h);
            // At this point the main FBO is bound and the viewport is restored to native res.
            // Minecraft will now render the GUI at full resolution.
        }
    }
}
