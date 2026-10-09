package com.armaturemc.client;

import com.google.gson.*;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClientArmorTest {
    @Test void nativeItemCoordinatesBecomeBodyLocalArmorAndKeepAtlasUvs() {
        var layer = ClientArmor.parse(fixture()).getFirst();
        assertEquals(EquipmentSlot.CHEST, layer.slot());
        var vertices = layer.quads().getFirst().vertices();
        assertEquals(-2.8125 / 16, vertices.getFirst().x(), 1e-6);
        assertEquals(.9375 / 16, vertices.getFirst().y(), 1e-6);
        assertEquals(2.8125 / 16, vertices.getFirst().z(), 1e-6);
        assertEquals(48.0 / 64, vertices.getFirst().u(), 1e-6);
        assertEquals(20.0 / 32, vertices.getFirst().v(), 1e-6);
        assertEquals(1, layer.quads().getFirst().normal().length(), 1e-6);
        assertEquals(1, layer.model().allParts().size());
    }

    @Test void nonArmorAndDuplicateSlotsCannotEnterEquipmentRenderer() {
        JsonArray layers = fixture(); layers.get(0).getAsJsonObject().addProperty("slot", "MAINHAND");
        assertThrows(IllegalArgumentException.class, () -> ClientArmor.parse(layers));
        JsonArray duplicate = fixture(); duplicate.add(duplicate.get(0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> ClientArmor.parse(duplicate));
    }

    @Test void legacyDoubleSidedMinecraftTemplateKeepsBothCubesAndObjectRotation() {
        JsonArray layers = fixture();
        JsonObject cube = layers.get(0).getAsJsonObject().getAsJsonArray("cubes").get(0).getAsJsonObject();
        cube.add("rotation", JsonParser.parseString("{\"axis\":\"y\",\"angle\":0,\"origin\":[8,8,8]}"));
        layers.get(0).getAsJsonObject().getAsJsonArray("cubes").add(cube.deepCopy());
        assertEquals(2, ClientArmor.parse(layers).getFirst().quads().size());
    }

    @Test void equipmentRenderingKeepsAuthoredSizeAndBypassesCachedVanillaCuboids() throws Exception {
        var layer = ClientArmor.parse(fixture()).getFirst();
        var mesh = layer.model().root().getRandomCube(RandomSource.create(0));
        // Sodium injects its constructor-cached geometry into vanilla Cube.compile.
        // Equipment rendering must dispatch to our mesh implementation instead.
        assertNotEquals(ModelPart.Cube.class, mesh.getClass().getMethod("compile", PoseStack.Pose.class,
            VertexConsumer.class, int.class, int.class, int.class).getDeclaringClass());
        assertEquals(5.625, mesh.maxX - mesh.minX, 1e-6);
        assertEquals(13.125, mesh.maxY - mesh.minY, 1e-6);
        assertRenderedMesh(layer, new PoseStack());
    }

    @Test void legacyArmorKeepsRotationUvsAndEquipmentAttributesUnderAnimatedBoneScale() {
        JsonArray layers = fixture();
        JsonObject cube = layers.get(0).getAsJsonObject().getAsJsonArray("cubes").get(0).getAsJsonObject();
        cube.add("rotation", JsonParser.parseString("{\"axis\":\"y\",\"angle\":27,\"origin\":[8,8,8]}"));
        layers.get(0).getAsJsonObject().getAsJsonArray("cubes").add(cube.deepCopy());
        var layer = ClientArmor.parse(layers).getFirst();
        PoseStack poses = new PoseStack();
        poses.translate(.3, -.7, -1.2);
        poses.scale(-1.4f, .8f, 1.1f);
        assertRenderedMesh(layer, poses);
    }

    private static void assertRenderedMesh(ClientArmor.Layer layer, PoseStack poses) {
        List<RenderedVertex> emitted = new ArrayList<>();
        VertexConsumer buffer = (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(),
            new Class<?>[] {VertexConsumer.class}, (proxy, method, args) -> {
                if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
                if (method.getName().equals("addVertex")) {
                    var vertex = new RenderedVertex();
                    vertex.position = new Vector3f((float) args[0], (float) args[1], (float) args[2]);
                    emitted.add(vertex);
                } else {
                    var vertex = emitted.getLast();
                    switch (method.getName()) {
                        case "setColor" -> vertex.color = args.length == 1 ? (int) args[0]
                            : (int) args[3] << 24 | (int) args[0] << 16 | (int) args[1] << 8 | (int) args[2];
                        case "setUv" -> { vertex.u = (float) args[0]; vertex.v = (float) args[1]; }
                        case "setUv1" -> vertex.overlay = (int) args[0] | (int) args[1] << 16;
                        case "setUv2" -> vertex.light = (int) args[0] | (int) args[1] << 16;
                        case "setNormal" -> vertex.normal = new Vector3f((float) args[0], (float) args[1], (float) args[2]);
                    }
                }
                return proxy;
            });
        int light = 0x00a000b0, overlay = 0x00050007, dyeColor = 0xff4387ab;
        layer.model().renderToBuffer(poses, buffer, light, overlay, dyeColor);
        assertEquals(layer.quads().size() * 4, emitted.size());
        int index = 0;
        for (var quad : layer.quads()) for (var vertex : quad.vertices()) {
            var actual = emitted.get(index++);
            Vector3f expected = poses.last().pose().transformPosition(new Vector3f(vertex.x(), vertex.y(), vertex.z()));
            assertTrue(expected.equals(actual.position, 1e-6f), "Authored armor size and bone position");
            assertEquals(vertex.u(), actual.u, 1e-6);
            assertEquals(vertex.v(), actual.v, 1e-6);
            Vector3f normal = poses.last().transformNormal(quad.normal(), new Vector3f());
            assertTrue(normal.equals(actual.normal, 1e-6f));
            assertEquals(dyeColor, actual.color);
            assertEquals(light, actual.light);
            assertEquals(overlay, actual.overlay);
        }
    }

    private static final class RenderedVertex {
        Vector3f position, normal;
        float u, v;
        int color, light, overlay;
    }

    private static JsonArray fixture() {
        return JsonParser.parseString("""
            [{"slot":"CHEST","cubes":[{"from":[5.1875,-4.1875,5.1875],"to":[10.8125,8.9375,10.8125],
              "faces":{"north":{"texture":"#0","uv":[11,10,12,16]}}}]}]
            """).getAsJsonArray();
    }
}
