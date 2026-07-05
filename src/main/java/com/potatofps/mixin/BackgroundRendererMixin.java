package com.potatofps.mixin;

import com.potatofps.PotatoFPS;
import com.potatofps.render.FogCuller;
import net.minecraft.client.render.BackgroundRenderer;
import net.minecraft.client.render.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * BackgroundRendererMixin - Captures fog parameters set by vanilla.
 *
 * TARGET: net.minecraft.client.render.BackgroundRenderer
 *
 * WHAT VANILLA DOES:
 *   BackgroundRenderer.applyFog() sets OpenGL fog state (GL_FOG_END distance)
 *   based on the current render distance and dimension. We intercept this to
 *   capture the fog end distance and feed it to FogCuller.
 *
 * WHY WE NEED THIS:
 *   FogCuller needs to know the exact fog end distance to make culling decisions.
 *   Reading it from BackgroundRenderer is more accurate than computing it ourselves
 *   because BackgroundRenderer handles special cases:
 *   - Blindness effect (very short fog distance)
 *   - Being underwater (shorter fog)
 *   - Being in lava (fog distance = 0)
 *   - Night vision (extends fog)
 *   Without this injection, FogCuller would use a rough approximation that could
 *   either over-cull (pop-in artifacts) or under-cull (wasted draw calls).
 */
@Mixin(BackgroundRenderer.class)
public abstract class BackgroundRendererMixin {

    /**
     * Inject after fog setup to capture the applied fog end distance.
     * Uses @At("RETURN") to run after all vanilla fog calculations complete.
     */
    @Inject(
        method = "applyFog",
        at = @At("RETURN"),
        require = 0
    )
    private static void potatofps$onApplyFog(
            Camera camera,
            BackgroundRenderer.FogType fogType,
            float viewDistance,
            boolean thickFog,
            float tickProgress,
            CallbackInfo ci
    ) {
        if (PotatoFPS.CONFIG.getConfig().fogCulling) {
            // Update FogCuller with the actual fog end distance used by vanilla.
            // viewDistance here is the render distance in chunks * 16 (blocks per chunk).
            // We pass it directly; FogCuller applies the 0.9 safety margin internally.
            FogCuller.setFogEnd(viewDistance);
        }
    }
}
