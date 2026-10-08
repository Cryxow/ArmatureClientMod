package com.armaturemc.client;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClientModelTest {
    private static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aCioAAAAASUVORK5CYII=";

    @Test void zeroScaleCannotSubmitInvalidNormalsWhileSmallAndMirroredScalesRemainDrawable() {
        var collapsed = new org.joml.Matrix4f().translation(.4f, -.3f, -1).scale(0);
        var unsafe = new com.mojang.blaze3d.vertex.PoseStack();
        unsafe.mulPose(collapsed);
        assertFalse(unsafe.last().normal().isFinite(), "Minecraft inverse-transpose is undefined at scale zero");
        assertFalse(ClientModel.drawable(collapsed));
        for (float scale : new float[]{.00001f, .1f, 1, -1, -2}) {
            var matrix = new org.joml.Matrix4f().rotateY(.4f).scale(scale, .5f, 2);
            assertTrue(ClientModel.drawable(matrix));
            var poses = new com.mojang.blaze3d.vertex.PoseStack(); poses.mulPose(matrix);
            assertTrue(poses.last().normal().isFinite());
        }
    }

    @Test void authoredCubeUsesBoneLocalBlockCoordinatesAndOwnUv() {
        ClientModel model = ClientModel.parse(bundle(0));
        var quad = model.parts.getFirst().quads().getFirst();
        assertEquals(4, quad.vertices().size());
        assertEquals(-1, quad.vertices().getFirst().x(), 1e-6);
        assertEquals(1, quad.vertices().getFirst().y(), 1e-6);
        assertEquals(0, quad.vertices().getFirst().z(), 1e-6);
        assertEquals(1, quad.normal().length(), 1e-6);
    }
    @Test void cubeRotationRemainsGeometryRatherThanAnItemDisplayPartition() {
        var unrotated = ClientModel.parse(bundle(0)).parts.getFirst().quads().getFirst();
        var rotated = ClientModel.parse(bundle(30)).parts.getFirst().quads().getFirst();
        assertNotEquals(unrotated.vertices().getFirst().x(), rotated.vertices().getFirst().x());
        assertEquals(1, rotated.normal().length(), 1e-6);
    }
    @Test void oversizedPngHeaderIsRejectedBeforeNativeImageDecode() {
        JsonObject root = JsonParser.parseString(new String(bundle(0), StandardCharsets.UTF_8)).getAsJsonObject();
        byte[] png = java.util.Base64.getDecoder().decode(PNG);
        java.nio.ByteBuffer.wrap(png, 16, 4).putInt(8192);
        root.getAsJsonObject("textures").getAsJsonObject("0").addProperty("png", java.util.Base64.getEncoder().encodeToString(png));
        assertThrows(IllegalArgumentException.class, () -> ClientModel.parse(root.toString().getBytes(StandardCharsets.UTF_8)));
    }
    @Test void legacyLogicalUvGridIsIndependentOfTexturePixelResolution() {
        for (int grid : new int[]{2, 16, 32}) {
            JsonObject root = JsonParser.parseString(new String(bundle(0), StandardCharsets.UTF_8)).getAsJsonObject();
            var texture = root.getAsJsonObject("textures").getAsJsonObject("0");
            texture.addProperty("width", grid); texture.addProperty("height", grid);
            var face = root.getAsJsonArray("bones").get(0).getAsJsonObject().getAsJsonArray("cubes")
                .get(0).getAsJsonObject().getAsJsonObject("faces").getAsJsonObject("north");
            face.add("uv", JsonParser.parseString("[0,0," + grid + "," + grid + "]"));
            var vertices = ClientModel.parse(root.toString().getBytes(StandardCharsets.UTF_8)).parts.getFirst().quads().getFirst().vertices();
            assertEquals(1, vertices.getFirst().u(), 1e-6);
            assertEquals(0, vertices.getFirst().v(), 1e-6);
            assertEquals(0, vertices.get(2).u(), 1e-6);
            assertEquals(1, vertices.get(2).v(), 1e-6);
        }
    }

    @Test void invalidLogicalUvDimensionsAreStillRejected() {
        for (int grid : new int[]{0, -1, 65537}) {
            JsonObject root = JsonParser.parseString(new String(bundle(0), StandardCharsets.UTF_8)).getAsJsonObject();
            root.getAsJsonObject("textures").getAsJsonObject("0").addProperty("width", grid);
            assertThrows(IllegalArgumentException.class, () -> ClientModel.parse(root.toString().getBytes(StandardCharsets.UTF_8)));
        }
    }
    private static byte[] bundle(int rotation) {
        return ("""
            {"textures":{"0":{"png":"%s","width":1,"height":1}},
             "bones":[{"id":"bone","role":"none","pivot":[0,0,0],"cubes":[
              {"from":[0,0,0],"to":[16,16,16],"origin":[0,0,0],"rotation":[0,0,%d],
               "faces":{"north":{"texture":0,"uv":[0,0,1,1]}}}]}]}
            """).formatted(PNG, rotation).getBytes(StandardCharsets.UTF_8);
    }
}
