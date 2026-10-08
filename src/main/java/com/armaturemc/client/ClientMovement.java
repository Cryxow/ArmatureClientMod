package com.armaturemc.client;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/** Minecraft 1.21.8's interpolated vanilla walking distance. */
final class ClientMovement {
    private ClientMovement() { }
    static float distance(LocalPlayer player, float partialTick) {
        return Mth.lerp(partialTick, player.walkDistO, player.walkDist);
    }
}
