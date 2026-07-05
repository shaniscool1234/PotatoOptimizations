package com.potatofps.gui;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * ModMenuIntegration - Registers PotatoFPS's config screen with ModMenu.
 *
 * ModMenu is a Fabric mod that shows all installed mods in a list with optional
 * "Settings" buttons. Implementing ModMenuApi here provides that button,
 * opening our PotatoConfigScreen when clicked.
 *
 * This class is only loaded when ModMenu is actually installed (it's declared as
 * a "suggests" optional dependency in fabric.mod.json). If ModMenu is absent,
 * this class is never referenced and causes no ClassNotFoundException.
 *
 * The config screen is also accessible via /potatofps config (see commands).
 */
@Environment(EnvType.CLIENT)
public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        // Return a factory that creates our config screen with the current screen as parent
        return parent -> new PotatoVideoOptionsScreen(parent, net.minecraft.client.MinecraftClient.getInstance().options);
    }
}
