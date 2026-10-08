package com.armaturemc.client;

import com.armaturemc.client.protocol.ClientProtocol;
import com.armaturemc.renderer.internal.animation.MolangContext;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real native compiler exports, with server matrices captured before any GPU/render hook. */
class LegacyClientModelTest {
    @Test void genericAndRifleBundlesParseAndMatchEveryExportedAuthoredAndOffhandClip() throws Exception {
        for (String name : new String[]{"arm_generic", "arm_rifle"}) {
            byte[] bytes;
            try (var stream = getClass().getResourceAsStream("/legacy-client/" + name + ".json")) {
                bytes = Objects.requireNonNull(stream, name).readAllBytes();
            }
            var fixture = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            assertTrue(fixture.get("regression_source_hash").getAsString().matches("[a-f0-9]{64}"));
            var model = ClientModel.parse(bytes);
            assertNotNull(model.animation, name);
            assertTrue(model.parts.stream().anyMatch(part -> part.role().equals("legacy_right_arm")), name);
            assertTrue(model.parts.stream().anyMatch(part -> part.role().equals("legacy_right_forearm")), name);
            int samples = 0; boolean offhand = false;
            for (var entry : fixture.getAsJsonArray("serverSamples")) {
                var sample = entry.getAsJsonObject();
                String clip = sample.get("clip").getAsString();
                offhand |= clip.endsWith("__armature_offhand");
                List<ClientProtocol.Bone> bones = new ArrayList<>();
                sample.getAsJsonObject("bones").entrySet().forEach(bone -> bones.add(new ClientProtocol.Bone(
                    bone.getKey(), bone.getValue().getAsJsonObject().get("visible").getAsBoolean())));
                model.animation.resetPlayback();
                var control = new JsonObject(); var loop = new JsonObject();
                loop.addProperty("name", clip); loop.add("elapsed", sample.get("elapsed"));
                loop.addProperty("in", 0); loop.addProperty("out", 0); control.add("loop", loop);
                long now = 1_000_000_000L;
                var frame = new ClientProtocol.Frame(0, UUID.randomUUID(), "a".repeat(64), 1, true,
                    new int[0], bones, ClientProtocol.View.defaults(), control.toString());
                model.animation.receive(frame, now, MolangContext.defaults());
                var actual = model.animation.matrices(now, MolangContext.defaults(), bones);
                for (var bone : sample.getAsJsonObject("bones").entrySet()) {
                    var expected = bone.getValue().getAsJsonObject();
                    if (!expected.get("visible").getAsBoolean()) { assertNull(actual.get(bone.getKey())); continue; }
                    float[] values = new float[16]; var matrix = expected.getAsJsonArray("matrix");
                    for (int i = 0; i < 16; i++) values[i] = matrix.get(i).getAsFloat();
                    assertNotNull(actual.get(bone.getKey()), name + " " + bone.getKey());
                    assertTrue(new Matrix4f().set(values).equals(actual.get(bone.getKey()), 1e-4f),
                        name + " " + clip + " at " + sample.get("elapsed") + " bone " + bone.getKey());
                }
                samples++;
            }
            assertTrue(samples > 50, name); assertTrue(offhand, name);
        }
    }
}
