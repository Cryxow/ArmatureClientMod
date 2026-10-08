package com.armaturemc.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/** Immediate hand-pass geometry used by Minecraft 1.21.8. */
final class ClientGeometry {
    private ClientGeometry() { }
    static void draw(ClientModel.Quad quad, ResourceLocation texture, PoseStack poses, MultiBufferSource buffers, int light) {
        var buffer = buffers.getBuffer(RenderType.entityTranslucent(texture));
        var pose = poses.last();
        for (var vertex : quad.vertices()) {
            buffer.addVertex(pose, vertex.x(), vertex.y(), vertex.z()).setColor(255, 255, 255, 255)
                .setUv(vertex.u(), vertex.v()).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                .setNormal(pose, quad.normal().x, quad.normal().y, quad.normal().z);
        }
    }
}
