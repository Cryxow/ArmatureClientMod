package com.armaturemc.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Loaded by Mod Menu only, keeping that mod optional. */
public final class ArmatureModMenu implements ModMenuApi {
    @Override public ConfigScreenFactory<?> getModConfigScreenFactory() { return ArmatureConfigScreen::new; }
}
