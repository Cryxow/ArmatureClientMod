package com.armaturemc.client;

import com.armaturemc.client.mixin.CameraFovAccessor;
import net.minecraft.client.Minecraft;

/** Camera owns separate world and hand FOV values in 26.x. */
final class ClientProjection {
    static float fov(Minecraft client, float partialTick, boolean world) {
        var camera = client.gameRenderer.getMainCamera();
        return world ? camera.getFov() : ((CameraFovAccessor)camera).armature$getHudFov();
    }
}
