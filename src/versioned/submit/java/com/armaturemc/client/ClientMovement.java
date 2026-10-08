package com.armaturemc.client;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/** Interpolate the replacement move-distance counter once per player tick. */
final class ClientMovement {
    private static LocalPlayer owner;
    private static int tick;
    private static float previous, current;
    private ClientMovement() { }
    static float distance(LocalPlayer player, float partialTick) {
        if (player != owner || player.tickCount < tick) {
            owner = player; tick = player.tickCount; previous = current = player.moveDist;
        } else if (player.tickCount != tick) {
            previous = player.tickCount == tick + 1 ? current : player.moveDist;
            current = player.moveDist; tick = player.tickCount;
        }
        return Mth.lerp(partialTick, previous, current);
    }
}
