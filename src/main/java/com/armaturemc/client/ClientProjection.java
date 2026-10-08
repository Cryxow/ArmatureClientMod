package com.armaturemc.client;

import com.armaturemc.client.mixin.GameRendererAccessor;
import net.minecraft.client.Minecraft;

/** Read the existing projections without changing Minecraft's camera FOV. */
final class ClientProjection {
    static float fov(Minecraft client, float partialTick, boolean world) {
        return ((GameRendererAccessor)client.gameRenderer).armature$getFov(
            client.gameRenderer.getMainCamera(), partialTick, world);
    }
}
