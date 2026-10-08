package com.armaturemc.client;

import com.armaturemc.client.protocol.ClientProtocol;
import com.armaturemc.client.mixin.GameRendererAccessor;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Viewport attachment and configured viewmodel FOV, independent of world/tick positions. */
final class ClientViewTransform {
    private ClientViewTransform() { }

    static Matrix4f forFrame(ClientProtocol.Frame frame, float partialTick) {
        Minecraft client = Minecraft.getInstance();
        var camera = client.gameRenderer.getMainCamera();
        var renderer = (GameRendererAccessor)client.gameRenderer;
        float handFov = renderer.armature$getFov(camera, partialTick, false);
        float targetFov = frame.view().fov() == 0 ? renderer.armature$getFov(camera, partialTick, true) : frame.view().fov();
        float originY = 0;
        if (frame.view().mountedOrigin()) {
            // Legacy carrier attachment, expressed locally. Subtracting the interpolated
            // camera position from a tick-based passenger position caused movement jitter.
            originY = client.player.getDimensions(client.player.getPose()).height() + .01f
                - client.player.getEyeHeight();
        }
        // Both source formats are camera-attached, including their Y origin. The
        // plugin's y-lock option controls its shader path, not this viewport path.
        return matrix(handFov, targetFov, originY);
    }

    static Matrix4f matrix(float handFov, float targetFov, float originY) {
        float scale = (float)(Math.tan(Math.toRadians(handFov) / 2)
            / Math.tan(Math.toRadians(targetFov) / 2));
        return new Matrix4f().scaling(scale, scale, 1)
            .translate(0, originY, 0);
    }

    /** Cancels the active view matrix, without inheriting vanilla hand bob/hurt transforms. */
    static Matrix4f viewport(Matrix4fc modelView) { return new Matrix4f(modelView).invert(); }
}
