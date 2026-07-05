package com.potatofps.mixin;

import com.potatofps.gui.PotatoVideoOptionsScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.VideoOptionsScreen;
import net.minecraft.client.option.GameOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * VideoOptionsScreenMixin — Replaces vanilla Video Settings with PotatoFPS's
 * custom screen, which merges all vanilla video options with our optimization
 * settings in one unified, better-looking UI.
 *
 * Strategy: inject into <init> to capture the parent screen (field is private/
 * final so @Shadow is fragile across obfuscation layers), then cancel init()
 * and open our replacement screen instead.
 */
@Mixin(VideoOptionsScreen.class)
public abstract class VideoOptionsScreenMixin extends Screen {

    @Unique
    private Screen potatofps$parent;

    protected VideoOptionsScreenMixin(net.minecraft.text.Text title) {
        super(title);
    }

    /** Capture the parent screen before anything else initialises. */
    @Inject(method = "<init>", at = @At("TAIL"), require = 0)
    private void potatofps$captureParent(Screen parent, GameOptions options, CallbackInfo ci) {
        this.potatofps$parent = parent;
    }

    /**
     * Replace vanilla's init() entirely.
     * We open our custom screen with the same parent so the Back button works normally.
     */
    @Inject(method = "init", at = @At("HEAD"), cancellable = true, require = 0)
    private void potatofps$replaceWithCustomScreen(CallbackInfo ci) {
        if (this.client != null) {
            Screen parent = this.potatofps$parent;
            this.client.setScreen(new PotatoVideoOptionsScreen(parent, this.client.options));
            ci.cancel();
        }
    }
}
