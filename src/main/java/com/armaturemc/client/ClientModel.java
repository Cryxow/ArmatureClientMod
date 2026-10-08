package com.armaturemc.client;

import com.armaturemc.client.protocol.ClientProtocol;
import com.google.gson.*;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Real textured quads in the hand pass. No virtual entities, billboards or core-shader markers. */
final class ClientModel implements AutoCloseable {
    record Texture(byte[] png, int width, int height) { }
    record Vertex(float x, float y, float z, float u, float v) { }
    record Quad(String texture, List<Vertex> vertices, Vector3f normal) { }
    record Part(String id, String role, List<Quad> quads, List<ClientArmor.Layer> armor) { }
    private static final JsonObject BODY_PARTS = bodyParts();
    private static final Map<Boolean, Map<String, List<Quad>>> SKIN_PARTS = Map.of(false, skinParts(false), true, skinParts(true));
    private static long textureGeneration;
    final List<Part> parts;
    final ClientAnimation animation;
    private JsonObject control = new JsonObject();
    private final Map<String, Texture> sources;
    private final long cacheBytes;
    private final Map<String, ResourceLocation> textures = new HashMap<>();
    private ClientModel(List<Part> parts, Map<String, Texture> textures, ClientAnimation animation, long cacheBytes) {
        this.parts = List.copyOf(parts); this.sources = Map.copyOf(textures);
        this.animation = animation; this.cacheBytes = cacheBytes;
    }

    long cacheBytes() { return cacheBytes; }

    /** Called on a worker. Geometry and PNG-header budgets are checked before allocating GPU images. */
    static ClientModel parse(byte[] bytes) {
        if (bytes.length > ClientProtocol.MAX_MODEL) throw new IllegalArgumentException("Model exceeds budget");
        JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        Map<String, Texture> textures = new HashMap<>(); int pixels = 0;
        JsonObject textureJson = root.getAsJsonObject("textures");
        if (textureJson.size() > 32) throw new IllegalArgumentException("Too many textures");
        for (var entry : textureJson.entrySet()) {
            JsonObject texture = entry.getValue().getAsJsonObject();
            byte[] png = Base64.getDecoder().decode(texture.get("png").getAsString());
            if (png.length < 24 || ByteBuffer.wrap(png).getLong() != 0x89504e470d0a1a0aL
                || ByteBuffer.wrap(png, 12, 4).getInt() != 0x49484452) throw new IllegalArgumentException("Invalid PNG");
            int width = ByteBuffer.wrap(png, 16, 4).getInt(), height = ByteBuffer.wrap(png, 20, 4).getInt();
            if (width < 1 || height < 1 || width > 2048 || height > 2048
                || (pixels += width * height) > 8_388_608) throw new IllegalArgumentException("Texture pixel budget");
            int uvWidth = texture.get("width").getAsInt(), uvHeight = texture.get("height").getAsInt();
            // Blockbench UV dimensions are a logical grid, independent of PNG resolution.
            // Legacy marker textures commonly use a 2x2 PNG on a 16x16 UV grid.
            if (uvWidth < 1 || uvHeight < 1 || uvWidth > 65536 || uvHeight > 65536) {
                throw new IllegalArgumentException("Invalid texture UV dimensions");
            }
            textures.put(entry.getKey(), new Texture(png, uvWidth, uvHeight));
        }
        JsonArray bones = root.getAsJsonArray("bones");
        if (bones.size() > ClientProtocol.MAX_BONES) throw new IllegalArgumentException("Too many bones");
        var parts = new ArrayList<Part>(); Set<String> ids = new HashSet<>(); int cubes = 0;
        for (JsonElement element : bones) {
            JsonObject bone = element.getAsJsonObject(); String id = bone.get("id").getAsString();
            if (!ids.add(id) || id.length() > 128) throw new IllegalArgumentException("Invalid bone id");
            Vector3f pivot = vector(bone.getAsJsonArray("pivot")); var quads = new ArrayList<Quad>();
            for (JsonElement cube : bone.getAsJsonArray("cubes")) {
                if (++cubes > 4096) throw new IllegalArgumentException("Cube budget");
                compileCube(cube.getAsJsonObject(), pivot, textures, quads);
            }
            parts.add(new Part(id, bone.get("role").getAsString(), ClientSurfaceSeparation.resolve(quads),
                ClientArmor.parse(bone.getAsJsonArray("armor"))));
        }
        long cacheBytes = bytes.length * 2L + pixels * 8L + cubes * 6L * 512;
        return new ClientModel(parts, textures, root.has("animation") ? new ClientAnimation(root.getAsJsonObject("animation")) : null,
            cacheBytes);
    }

    void receive(ClientProtocol.Frame frame, long now, com.armaturemc.renderer.internal.animation.MolangContext viewer) {
        control = JsonParser.parseString(frame.control()).getAsJsonObject();
        if (animation != null) animation.receive(frame, now, viewer);
    }

    void upload(String hash, int slot) throws IOException {
        long generation = ++textureGeneration;
        try {
            for (var entry : sources.entrySet()) {
                // A cached instance may move to another channel. Its textures must have a unique
                // lifetime, so disposing it cannot release a newer instance's identical bundle.
                ResourceLocation location = ResourceLocation.fromNamespaceAndPath("armature_client",
                    hash + "/" + slot + "/" + generation + "/" + entry.getKey());
                NativeImage image = NativeImage.read(entry.getValue().png());
                DynamicTexture texture;
                try { texture = new DynamicTexture(() -> "Armature " + hash, image); }
                catch (RuntimeException failure) { image.close(); throw failure; }
                Minecraft.getInstance().getTextureManager().register(location, texture);
                textures.put(entry.getKey(), location);
            }
        } catch (IOException | RuntimeException failure) { close(); throw failure; }
    }

    void render(Map<String, Matrix4f> matrices, PoseStack stack, MultiBufferSource buffers, int light, ClientHeldItems items) {
        var minecraft = Minecraft.getInstance();
        for (Part part : parts) {
            Matrix4f matrix = matrices.get(part.id()); if (matrix == null) continue;
            // Zero-scale authored bones remain in the animation hierarchy, but
            // must not enter PoseStack's inverse-transpose normal calculation.
            // This covers their skin, armor and held-item geometry together.
            if (!drawable(matrix)) continue;
            stack.pushPose();
            try {
            stack.mulPose(matrix);
            for (Quad quad : part.quads()) {
                draw(quad, textures.get(quad.texture()), stack, buffers, light);
            }
            boolean slim = minecraft.player.getSkin().model() == PlayerSkin.Model.SLIM;
            for (Quad quad : SKIN_PARTS.get(slim).getOrDefault(part.role(), List.of())) {
                draw(quad, minecraft.player.getSkin().texture(), stack, buffers, light);
            }
            ClientArmor.render(part.armor(), stack, buffers, light);
            if (part.role().equals("item_right") || part.role().equals("item_left")) {
                boolean offhand = part.role().equals("item_left");
                var item = offhand ? minecraft.player.getOffhandItem() : minecraft.player.getMainHandItem();
                if (control.has("deferredItems") && control.get("deferredItems").getAsBoolean()) {
                    item = items == null ? net.minecraft.world.item.ItemStack.EMPTY : offhand ? items.off() : items.main();
                }
                var model = control.get(offhand ? "offItemModel" : "mainItemModel");
                String resolved = model == null || model.isJsonNull() ? null : model.getAsString();
                // ItemDisplayRenderer applies Ry(pi) before its resolved third-person hand context.
                stack.mulPose(com.mojang.math.Axis.YP.rotation((float)Math.PI));
                ClientHeldItem.render(minecraft.getItemRenderer(), item, resolved, offhand,
                    stack, buffers, minecraft.level, light, minecraft.player.getId());
            }
            } finally { stack.popPose(); }
        }
    }

    static boolean drawable(Matrix4f matrix) { return matrix.determinant3x3() != 0; }

    private static void draw(Quad quad, ResourceLocation texture, PoseStack stack, MultiBufferSource buffers, int light) {
        ClientGeometry.draw(quad, texture, stack, buffers, light);
    }

    private static JsonObject bodyParts() {
        try (var stream = ClientModel.class.getResourceAsStream("/armature/body-parts-v2.json")) {
            if (stream == null) throw new IllegalStateException("Missing v2 body part contract");
            return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }

    /** Shares the native v2 dimensions and UVs, using the client's live Steve/Alex skin and sleeves. */
    private static Map<String, List<Quad>> skinParts(boolean slim) {
        Map<String, List<Quad>> parts = new HashMap<>();
        JsonObject definitions = BODY_PARTS.deepCopy();
        // Legacy DynamicUV arms use six-pixel slices and different end caps from explicit v2 parts.
        for (boolean left : new boolean[] {false, true}) for (String segment : List.of("arm", "forearm", "full_arm")) {
            boolean forearm = segment.equals("forearm"), full = segment.equals("full_arm");
            int height = full ? 12 : 6, baseX = left ? 36 : 44, eastX = left ? 32 : 40;
            int y = forearm ? (left ? 58 : 26) : (left ? 52 : 20), capY = left ? 48 : 16;
            JsonObject definition = new JsonObject();
            definition.add("size", vectorJson(new Vector3f(4, height, 4)));
            definition.add("center", vectorJson(new Vector3f(0, -height / 2f, 0)));
            definition.addProperty("slim", true);
            definition.addProperty("inflate", .25f * 16 / (8 * .9375f * 2));
            JsonObject faces = new JsonObject(), overlay = new JsonObject();
            faces.add("north", offset(baseX, y)); faces.add("south", offset(baseX + 8, y));
            faces.add("east", offset(eastX, y)); faces.add("west", offset(baseX + 4, y));
            faces.add(forearm ? "down" : "up", offset(forearm ? baseX + 4 : baseX, capY));
            if (full) faces.add("down", offset(baseX + 4, capY));
            for (var face : faces.entrySet()) {
                JsonArray uv = face.getValue().getAsJsonArray();
                overlay.add(face.getKey(), offset(uv.get(0).getAsInt() + (left ? 16 : 0), uv.get(1).getAsInt() + (left ? 0 : 16)));
            }
            definition.add("faces", faces); definition.add("overlayFaces", overlay);
            definitions.add("legacy_" + (left ? "left_" : "right_") + segment, definition);
        }
        for (var entry : definitions.entrySet()) {
            JsonObject definition = entry.getValue().getAsJsonObject();
            boolean slimArm = slim && definition.has("slim") && definition.get("slim").getAsBoolean();
            Vector3f size = vector(definition.getAsJsonArray("size")); if (slimArm) size.x -= 1;
            Vector3f center = vector(definition.getAsJsonArray("center"));
            var quads = new ArrayList<Quad>();
            for (String layer : List.of("faces", "overlayFaces")) {
                JsonObject cube = new JsonObject();
                cube.add("from", vectorJson(new Vector3f(center).sub(new Vector3f(size).mul(0.5f))));
                cube.add("to", vectorJson(new Vector3f(center).add(new Vector3f(size).mul(0.5f))));
                cube.addProperty("inflate", layer.equals("faces") ? 0 : definition.get("inflate").getAsFloat());
                JsonObject faces = new JsonObject();
                for (var faceEntry : definition.getAsJsonObject(layer).entrySet()) {
                    String direction = faceEntry.getKey(); JsonArray offset = faceEntry.getValue().getAsJsonArray();
                    float u = offset.get(0).getAsFloat() - (slimArm && Set.of("south", "west", "down").contains(direction) ? 1 : 0);
                    float v = offset.get(1).getAsFloat();
                    boolean horizontal = direction.equals("up") || direction.equals("down");
                    float width = direction.equals("east") || direction.equals("west") ? size.z : size.x;
                    float height = horizontal ? size.z : size.y;
                    JsonObject face = new JsonObject(); face.addProperty("texture", "skin");
                    JsonArray uv = new JsonArray(); uv.add(u); uv.add(v); uv.add(u + width); uv.add(v + height);
                    face.add("uv", uv); faces.add(direction, face);
                }
                cube.add("faces", faces);
                compileCube(cube, new Vector3f(), Map.of("skin", new Texture(new byte[0], 64, 64)), quads);
            }
            // Native v2 body geometry uses BetterModel's 0.9375 factor.
            var scaled = quads.stream().map(quad -> new Quad(quad.texture(), quad.vertices().stream()
                .map(vertex -> new Vertex(vertex.x() * .9375f, vertex.y() * .9375f, vertex.z() * .9375f,
                    vertex.u(), vertex.v())).toList(), quad.normal())).toList();
            parts.put(entry.getKey(), scaled);
        }
        return Map.copyOf(parts);
    }
    private static JsonArray vectorJson(Vector3f vector) {
        JsonArray result = new JsonArray(); result.add(vector.x); result.add(vector.y); result.add(vector.z); return result;
    }
    private static JsonArray offset(int u, int v) {
        JsonArray result = new JsonArray(); result.add(u); result.add(v); return result;
    }

    @Override public void close() {
        textures.values().forEach(location -> Minecraft.getInstance().getTextureManager().release(location)); textures.clear();
    }

    static void compileCube(JsonObject cube, Vector3f bonePivot, Map<String, Texture> textures, List<Quad> result) {
        Vector3f from = vector(cube.getAsJsonArray("from")), to = vector(cube.getAsJsonArray("to"));
        float inflate = cube.has("inflate") ? finite(cube.get("inflate").getAsFloat()) : 0;
        from.sub(inflate, inflate, inflate); to.add(inflate, inflate, inflate);
        Vector3f pivot = cube.has("origin") ? vector(cube.getAsJsonArray("origin")) : new Vector3f();
        Vector3f rotation = cube.has("rotation") ? vector(cube.getAsJsonArray("rotation")) : new Vector3f();
        Matrix4f transform = new Matrix4f().scaling(-1f / 16, 1f / 16, -1f / 16)
            .translate(new Vector3f(bonePivot).negate()).translate(pivot)
            .rotateZYX((float)Math.toRadians(rotation.z), (float)Math.toRadians(rotation.y), (float)Math.toRadians(rotation.x))
            .translate(new Vector3f(pivot).negate());
        float x = from.x, y = from.y, z = from.z, X = to.x, Y = to.y, Z = to.z;
        Map<String, float[][]> faces = Map.of(
            "north", new float[][]{{X,Y,z},{X,y,z},{x,y,z},{x,Y,z}},
            "south", new float[][]{{x,Y,Z},{x,y,Z},{X,y,Z},{X,Y,Z}},
            "east", new float[][]{{X,Y,Z},{X,y,Z},{X,y,z},{X,Y,z}},
            "west", new float[][]{{x,Y,z},{x,y,z},{x,y,Z},{x,Y,Z}},
            "up", new float[][]{{x,Y,z},{x,Y,Z},{X,Y,Z},{X,Y,z}},
            "down", new float[][]{{x,y,Z},{x,y,z},{X,y,z},{X,y,Z}});
        for (var entry : cube.getAsJsonObject("faces").entrySet()) {
            JsonObject face = entry.getValue().getAsJsonObject();
            if (!face.has("texture") || face.get("texture").isJsonNull()) continue;
            String textureId = face.get("texture").getAsString(); Texture texture = textures.get(textureId);
            if (texture == null || !faces.containsKey(entry.getKey())) throw new IllegalArgumentException("Unknown cube face texture");
            JsonArray uv = face.getAsJsonArray("uv"); if (uv.size() != 4) throw new IllegalArgumentException("Invalid UV");
            float u = finite(uv.get(0).getAsFloat()) / texture.width(), v = finite(uv.get(1).getAsFloat()) / texture.height();
            float U = finite(uv.get(2).getAsFloat()) / texture.width(), V = finite(uv.get(3).getAsFloat()) / texture.height();
            float[][] coords = {{U,v},{U,V},{u,V},{u,v}};
            int rotationSteps = face.has("rotation") ? face.get("rotation").getAsInt() / 90 : 0;
            var vertices = new ArrayList<Vertex>(4); float[][] positions = faces.get(entry.getKey());
            for (int i = 0; i < 4; i++) {
                Vector3f pos = transform.transformPosition(new Vector3f(positions[i]));
                float[] tex = coords[Math.floorMod(i - rotationSteps, 4)];
                vertices.add(new Vertex(pos.x, pos.y, pos.z, tex[0], tex[1]));
            }
            Vector3f normal = new Vector3f(vertices.get(1).x - vertices.get(0).x,
                vertices.get(1).y - vertices.get(0).y, vertices.get(1).z - vertices.get(0).z)
                .cross(new Vector3f(vertices.get(2).x - vertices.get(0).x,
                    vertices.get(2).y - vertices.get(0).y, vertices.get(2).z - vertices.get(0).z));
            if (normal.lengthSquared() > 0) normal.normalize();
            result.add(new Quad(textureId, List.copyOf(vertices), normal));
        }
    }
    private static Vector3f vector(JsonArray array) {
        if (array.size() != 3) throw new IllegalArgumentException("Invalid vector");
        return new Vector3f(finite(array.get(0).getAsFloat()), finite(array.get(1).getAsFloat()), finite(array.get(2).getAsFloat()));
    }
    private static float finite(float value) {
        if (!Float.isFinite(value) || Math.abs(value) > 65536) throw new IllegalArgumentException("Non-finite or oversized geometry"); return value;
    }
}
