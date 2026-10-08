package com.armaturemc.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/** Queue a pose snapshot in the native hand collector; drawing is deferred by Minecraft. */
final class ClientGeometry {
    private ClientGeometry() { }
    static void draw(ClientModel.Quad quad, ResourceLocation texture, PoseStack poses, SubmitNodeCollector collector, int light) {
        collector.submitCustomGeometry(poses, RenderType.entityTranslucent(texture), (pose, buffer) -> {
            for (var vertex : quad.vertices()) {
                buffer.addVertex(pose, vertex.x(), vertex.y(), vertex.z()).setColor(255, 255, 255, 255)
                    .setUv(vertex.u(), vertex.v()).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                    .setNormal(pose, quad.normal().x, quad.normal().y, quad.normal().z);
            }
        });
    }
}
