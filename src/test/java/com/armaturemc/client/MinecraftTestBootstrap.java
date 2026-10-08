package com.armaturemc.client;

final class MinecraftTestBootstrap {
    static void initialize() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }
}
