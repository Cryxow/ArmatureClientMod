package com.armaturemc.client;

/** 26.x binds registry components after bootstrap, when its lookup is available. */
final class MinecraftTestBootstrap {
    private static boolean initialized;
    static synchronized void initialize() {
        if (initialized) return;
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var lookup = net.minecraft.data.registries.VanillaRegistries.createLookup();
        net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(lookup)
            .forEach(net.minecraft.core.component.DataComponentInitializers.PendingComponents::apply);
        initialized = true;
    }
}
