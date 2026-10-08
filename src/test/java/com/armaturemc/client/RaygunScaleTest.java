package com.armaturemc.client;

import com.armaturemc.client.protocol.ClientProtocol;
import com.armaturemc.renderer.internal.animation.MolangContext;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RaygunScaleTest {
    private static JsonObject fixture() throws Exception {
        try (var stream = RaygunScaleTest.class.getResourceAsStream("/modern-client/raygun2.json")) {
            return JsonParser.parseString(new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        }
    }

    @Test void currentRaygunBundleMatchesNativeMatricesIncludingZeroAndGrowingScales() throws Exception {
        var fixture = fixture();
        assertEquals("e756f8cd793d03dacae95a5ba1839799c1deb87008c2716a051d5cac12d8d7a9",
            fixture.get("regression_source_hash").getAsString());
        var animation = new ClientAnimation(fixture.getAsJsonObject("animation"));
        int zeros = 0, scaled = 0;
        for (var element : fixture.getAsJsonArray("serverSamples")) {
            var sample = element.getAsJsonObject();
            List<ClientProtocol.Bone> bones = new ArrayList<>();
            sample.getAsJsonObject("bones").entrySet().forEach(entry -> bones.add(new ClientProtocol.Bone(entry.getKey(), true)));
            animation.resetPlayback();
            var loop = new JsonObject(); loop.add("name", sample.get("clip")); loop.add("elapsed", sample.get("elapsed"));
            var control = new JsonObject(); control.add("loop", loop);
            long now = 1_000_000_000L;
            animation.receive(new ClientProtocol.Frame(0, UUID.randomUUID(), "a".repeat(64), 1, true,
                new int[0], bones, ClientProtocol.View.defaults(), control.toString()), now, MolangContext.defaults());
            var matrices = animation.matrices(now, MolangContext.defaults(), bones);
            for (var entry : sample.getAsJsonObject("bones").entrySet()) {
                float[] values = new float[16]; var json = entry.getValue().getAsJsonObject().getAsJsonArray("matrix");
                for (int i = 0; i < 16; i++) values[i] = json.get(i).getAsFloat();
                var expected = new Matrix4f().set(values); var actual = matrices.get(entry.getKey());
                assertNotNull(actual);
                assertTrue(expected.equals(actual, 1e-4f), sample.get("clip") + " at " + sample.get("elapsed") + " " + entry.getKey());
                float scale = actual.getScale(new Vector3f()).x;
                if (scale == 0) { zeros++; assertFalse(ClientModel.drawable(actual)); }
                else if (scale < .999f) { scaled++; assertTrue(ClientModel.drawable(actual)); }
            }
        }
        assertTrue(zeros > 10); assertTrue(scaled > 0);
    }

    @Test void equipScaleAdvancesLocallyWhileZeroScaleBonesRemainHiddenBetweenServerFrames() throws Exception {
        var doc = fixture().getAsJsonObject("animation");
        var animation = new ClientAnimation(doc);
        List<ClientProtocol.Bone> bones = new ArrayList<>(); Map<String, String> ids = new HashMap<>();
        for (var group : doc.getAsJsonArray("groups")) {
            var bone = group.getAsJsonObject(); String id = bone.get("uuid").getAsString();
            ids.put(bone.get("name").getAsString(), id); bones.add(new ClientProtocol.Bone(id, true));
        }
        long start = 1_000_000_000L;
        animation.receive(new ClientProtocol.Frame(0, UUID.randomUUID(), "a".repeat(64), 1, true,
            new int[0], bones, ClientProtocol.View.defaults(),
            "{\"loop\":{\"name\":\"base\",\"elapsed\":0},\"action\":{\"name\":\"equip\",\"elapsed\":0}}"),
            start, MolangContext.defaults());
        float previous = 0;
        for (int i = 0; i <= 14; i++) {
            long now = start + Math.round(i / 144.0 * 1e9);
            var matrices = animation.matrices(now, MolangContext.defaults(), bones);
            float scale = matrices.get(ids.get("right_arm")).getScale(new Vector3f()).x;
            assertEquals(.1 + Math.min(i / 144.0 / .1, 1) * .9, scale, 1e-5);
            assertTrue(scale > previous); previous = scale;
            for (String hidden : new String[]{"left_arm", "battery_1", "battery_2"})
                assertFalse(ClientModel.drawable(matrices.get(ids.get(hidden))), hidden);
        }
    }
}
