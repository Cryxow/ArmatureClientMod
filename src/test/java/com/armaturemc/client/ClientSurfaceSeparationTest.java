package com.armaturemc.client;

import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClientSurfaceSeparationTest {
    private static ClientModel.Quad square(float x, float y, float z, boolean reverse, String texture) {
        var vertices = List.of(new ClientModel.Vertex(x, y, z, 0, 0), new ClientModel.Vertex(x + 1, y, z, 1, 0),
            new ClientModel.Vertex(x + 1, y + 1, z, 1, 1), new ClientModel.Vertex(x, y + 1, z, 0, 1));
        if (reverse) { var copy = new ArrayList<>(vertices); Collections.reverse(copy); vertices = copy; }
        return new ClientModel.Quad(texture, vertices, new Vector3f(0, 0, reverse ? -1 : 1));
    }

    @Test void coincidentFacesGetStableBoundedDepthWithoutChangingTextureUvOrNormals() {
        for (boolean opposite : new boolean[]{false, true}) {
            var input = List.of(square(0, 0, 0, false, "a"), square(0, 0, 0, opposite, "b"), square(0, 0, 0, false, "c"));
            var output = ClientSurfaceSeparation.resolve(input);
            assertSame(input.getFirst(), output.getFirst());
            assertNotEquals(output.get(0).vertices().getFirst().z(), output.get(1).vertices().getFirst().z());
            assertNotEquals(output.get(1).vertices().getFirst().z(), output.get(2).vertices().getFirst().z());
            assertEquals(output, ClientSurfaceSeparation.resolve(input), "Stable authored order, independent of camera frames");
            assertEquals(output, ClientSurfaceSeparation.resolve(output), "Resolved faces no longer overlap coplanarly");
            for (int i = 0; i < output.size(); i++) {
                assertEquals(input.get(i).texture(), output.get(i).texture());
                assertEquals(input.get(i).normal(), output.get(i).normal());
                for (int j = 0; j < 4; j++) {
                    var original = input.get(i).vertices().get(j); var adjusted = output.get(i).vertices().get(j);
                    assertEquals(original.x(), adjusted.x()); assertEquals(original.y(), adjusted.y());
                    assertEquals(original.u(), adjusted.u()); assertEquals(original.v(), adjusted.v());
                    assertTrue(Math.abs(adjusted.z() - original.z()) < ClientSurfaceSeparation.MAX_OFFSET);
                }
            }
        }
    }

    @Test void touchingSeparatedAndRotatedDisjointFacesKeepTheirExactGeometry() {
        var origin = square(0, 0, 0, false, "a");
        for (var other : List.of(square(1, 0, 0, false, "b"), square(2, 0, 0, false, "b"),
                square(0, 0, .001f, false, "b"))) {
            var input = List.of(origin, other); assertEquals(input, ClientSurfaceSeparation.resolve(input));
        }
        var diamond = new ClientModel.Quad("a", List.of(new ClientModel.Vertex(0, -1, 0, 0, 0),
            new ClientModel.Vertex(1, 0, 0, 1, 0), new ClientModel.Vertex(0, 1, 0, 1, 1),
            new ClientModel.Vertex(-1, 0, 0, 0, 1)), new Vector3f(0, 0, 1));
        var shifted = new ClientModel.Quad("b", diamond.vertices().stream().map(v ->
            new ClientModel.Vertex(v.x() + 1.5f, v.y() + 1.5f, 0, v.u(), v.v())).toList(), diamond.normal());
        var input = List.of(diamond, shifted);
        assertEquals(input, ClientSurfaceSeparation.resolve(input), "Overlapping bounding boxes are not overlapping polygons");
    }

    @Test void raygunPistolRemovesDegenerateEdgesAndSeparatesItsRealCoplanarFaces() throws Exception {
        byte[] bytes;
        try (var stream = getClass().getResourceAsStream("/modern-client/raygun2.json")) {
            bytes = Objects.requireNonNull(stream).readAllBytes();
        }
        var fixture = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        Map<String, ClientModel.Texture> textures = new HashMap<>();
        fixture.getAsJsonObject("textures").entrySet().forEach(entry -> {
            var texture = entry.getValue().getAsJsonObject();
            textures.put(entry.getKey(), new ClientModel.Texture(new byte[0], texture.get("width").getAsInt(), texture.get("height").getAsInt()));
        });
        var model = ClientModel.parse(bytes);
        int removed = 0, adjusted = 0;
        for (var element : fixture.getAsJsonArray("bones")) {
            var bone = element.getAsJsonObject(); var pivot = bone.getAsJsonArray("pivot");
            var local = new Vector3f(pivot.get(0).getAsFloat(), pivot.get(1).getAsFloat(), pivot.get(2).getAsFloat());
            List<ClientModel.Quad> raw = new ArrayList<>();
            for (var cube : bone.getAsJsonArray("cubes")) ClientModel.compileCube(cube.getAsJsonObject(), local, textures, raw);
            String id = bone.get("id").getAsString();
            var output = model.parts.stream().filter(part -> part.id().equals(id)).findFirst().orElseThrow().quads();
            removed += raw.size() - output.size();
            assertEquals(output, ClientSurfaceSeparation.resolve(output), "Unresolved coplanar faces in bone " + id);
            var valid = raw.stream().filter(quad -> quad.normal().lengthSquared() > 0).toList();
            for (int i = 0; i < output.size(); i++) {
                if (!valid.get(i).equals(output.get(i))) adjusted++;
                assertEquals(1, output.get(i).normal().length(), 1e-5);
            }
        }
        assertEquals(24, removed, "Flat cubes had 24 zero-area edge faces");
        assertTrue(adjusted >= 5, "Both barrel and main pistol coplanar overlaps must be covered");
    }
}
