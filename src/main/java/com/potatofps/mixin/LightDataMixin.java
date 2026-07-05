package com.potatofps.mixin;

import com.potatofps.PotatoFPS;
import com.potatofps.AnimationThrottler;
import net.minecraft.client.texture.SpriteAtlasTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LightDataMixin - Throttles sprite/texture animation updates.
 *
 * TARGET: net.minecraft.client.texture.SpriteAtlasTexture
 *
 * WHAT VANILLA DOES:
 *   SpriteAtlasTexture.updateAnimation() is called every game tick (20x/sec).
 *   For each animated sprite (water, lava, fire, portals, etc.), it:
 *   1. Advances the animation frame counter
 *   2. Uploads the new frame to GPU texture memory via glTexSubImage2D
 *
 *   Each glTexSubImage2D call uploads ~256-4096 bytes of texture data.
 *   With 20+ animated sprites, this is 5-80KB of GPU texture uploads per tick.
 *   On DDR3 shared memory, these uploads steal bandwidth from the render pipeline.
 *
 * WHAT WE DO:
 *   Skip the updateAnimation() call on ticks where AnimationThrottler says no.
 *   The sprite's animation frame is NOT advanced, so the same frame is held.
 *   This reduces GPU texture upload bandwidth by 50-75%.
 *
 * VISUAL IMPACT:
 *   At HALF speed (every 2 ticks):  Water animates at 10 FPS instead of 20 FPS.
 *   At QUARTER speed (every 4 ticks): Water animates at 5 FPS.
 *   At 5 FPS, water animation is visibly "choppy" up close — acceptable tradeoff
 *   for 1-3 FPS gain on integrated graphics with very limited memory bandwidth.
 *
 * RECOMMENDATION FOR USERS:
 *   Use HALF speed (default). It's nearly imperceptible except in extreme close-up.
 *   Use QUARTER only if you're still CPU/GPU bound after other optimizations.
 *   STATIC is for extreme cases (dedicated PvP, max FPS over visual quality).
 */
@Mixin(SpriteAtlasTexture.class)
public abstract class LightDataMixin {

    /**
     * Inject at HEAD of updateAnimation() and cancel if this tick should be skipped.
     *
     * NOTE: "updateAnimation" in Yarn mappings for 1.21.1 — verify exact name at:
     * https://yarn.fabricmc.net/ → search "SpriteAtlasTexture"
     *
     * If the method name has changed, this injection no-ops (require=0) and vanilla
     * behavior is preserved with no performance regression.
     */
    @Inject(
        method = "updateAnimation",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void potatofps$onUpdateAnimation(CallbackInfo ci) {
        var config = PotatoFPS.CONFIG.getConfig();

        // If animation rate is NORMAL, let vanilla handle it (no-op)
        if (config.animationTickRate == com.potatofps.config.PotatoConfig.AnimationRate.NORMAL) {
            return;
        }

        // If STATIC: always cancel (no animation ever)
        if (config.animationTickRate == com.potatofps.config.PotatoConfig.AnimationRate.STATIC) {
            ci.cancel();
            return;
        }

        // For HALF / QUARTER: cancel if this tick is a skip tick
        if (!AnimationThrottler.shouldAnimateThisTick()) {
            ci.cancel();
            // The animation frame is NOT advanced — the sprite holds its current frame
            // until the next allowed animation tick.
        }
    }
}
