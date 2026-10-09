package com.armaturemc.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import java.util.Set;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Vector3f;

/** Equipment geometry that retains authored faces when cube renderers are optimized. */
final class ClientArmorMesh extends ModelPart.Cube {
    private final List<ClientModel.Quad> quads;

    ClientArmorMesh(List<ClientModel.Quad> quads) {
        this(List.copyOf(quads), bounds(quads));
    }

    private ClientArmorMesh(List<ClientModel.Quad> quads, float[] bounds) {
        super(0, 0, bounds[0], bounds[1], bounds[2], bounds[3] - bounds[0],
            bounds[4] - bounds[1], bounds[5] - bounds[2], 0, 0, 0, false, 16, 16, Set.of());
        this.quads = quads;
    }

    @Override public void compile(PoseStack.Pose pose, VertexConsumer buffer, int light, int overlay, int color) {
        // Sodium caches constructor cuboids and bypasses later polygon replacements.
        // Emit the authored mesh here, outside the vanilla Cube.compile method it hooks.
        // Minecraft's equipment renderer still supplies dye, trim and glint consumers.
        for (var quad : quads) for (var vertex : quad.vertices()) {
            buffer.addVertex(pose, vertex.x(), vertex.y(), vertex.z()).setColor(color)
                .setUv(vertex.u(), vertex.v()).setOverlay(overlay).setLight(light)
                .setNormal(pose, quad.normal().x, quad.normal().y, quad.normal().z);
        }
    }

    private static float[] bounds(List<ClientModel.Quad> quads) {
        Vector3f min = new Vector3f(Float.POSITIVE_INFINITY), max = new Vector3f(Float.NEGATIVE_INFINITY);
        for (var quad : quads) for (var vertex : quad.vertices()) {
            Vector3f point = new Vector3f(vertex.x(), vertex.y(), vertex.z()).mul(16);
            min.min(point); max.max(point);
        }
        return new float[] {min.x, min.y, min.z, max.x, max.y, max.z};
    }
}
