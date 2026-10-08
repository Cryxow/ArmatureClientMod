package com.armaturemc.client;

import com.google.gson.*;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
import net.minecraft.client.resources.model.EquipmentAssetManager;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import org.joml.Vector3f;

/** Native body-local armor meshes, textured by Minecraft's equipment/dye/trim/glint renderer. */
public final class ClientArmor {
    private static EquipmentLayerRenderer renderer;
    record Layer(EquipmentSlot slot, Model model, List<ClientModel.Quad> quads) { }
    private ClientArmor() { }

    /** Called after the entity renderer's resource reload, so trim sprites always belong to the current atlas. */
    public static void reload(EquipmentAssetManager assets) {
        renderer = new EquipmentLayerRenderer(assets,
            Minecraft.getInstance().getModelManager().getAtlas(Sheets.ARMOR_TRIMS_SHEET));
    }

    static List<Layer> parse(JsonArray layers) {
        if (layers == null) return List.of();
        if (layers.size() > 2) throw new IllegalArgumentException("Armor layer budget");
        List<Layer> result = new ArrayList<>();
        Set<EquipmentSlot> slots = new HashSet<>();
        var uv = new ClientModel.Texture(new byte[0], 16, 16);
        for (var element : layers) {
            JsonObject layer = element.getAsJsonObject();
            EquipmentSlot slot = EquipmentSlot.valueOf(layer.get("slot").getAsString());
            if (!Set.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET).contains(slot)
                || !slots.add(slot)) throw new IllegalArgumentException("Invalid armor slot");
            JsonArray cubes = layer.getAsJsonArray("cubes");
            if (cubes.isEmpty() || cubes.size() > 2) throw new IllegalArgumentException("Armor cube budget");
            List<ClientModel.Quad> allQuads = new ArrayList<>();
            List<ModelPart.Cube> meshes = new ArrayList<>();
            for (var cubeEntry : cubes) {
            JsonObject cube = cubeEntry.getAsJsonObject().deepCopy();
            // ArmorModel's legacy templates use Minecraft element rotations rather than Blockbench arrays.
            if (cube.has("rotation") && cube.get("rotation").isJsonObject()) {
                JsonObject rotation = cube.getAsJsonObject("rotation");
                JsonArray angles = new JsonArray();
                for (String axis : List.of("x", "y", "z"))
                    angles.add(axis.equals(rotation.get("axis").getAsString()) ? rotation.get("angle").getAsFloat() : 0);
                cube.add("rotation", angles); cube.add("origin", rotation.getAsJsonArray("origin"));
            }
            for (var face : cube.getAsJsonObject("faces").entrySet()) {
                JsonObject definition = face.getValue().getAsJsonObject();
                definition.addProperty("texture", definition.get("texture").getAsString().replace("#", ""));
            }
            List<ClientModel.Quad> quads = new ArrayList<>();
            ClientModel.compileCube(cube, new Vector3f(8, 8, 8), Map.of("0", uv, "1", uv), quads);
            if (quads.isEmpty() || quads.size() > 6) throw new IllegalArgumentException("Invalid armor faces");
            Set<Direction> directions = EnumSet.noneOf(Direction.class);
            for (int i = 0; i < quads.size(); i++) directions.add(Direction.values()[i]);
            // Model's renderer accepts ModelPart geometry. Replace this private cube's polygons
            // with the already compiled quads; no global vanilla model is modified.
            ModelPart.Cube mesh = new ModelPart.Cube(0, 0, 0, 0, 0, 1, 1, 1,
                0, 0, 0, false, 16, 16, directions);
            for (int i = 0; i < quads.size(); i++) {
                var quad = quads.get(i);
                ModelPart.Vertex[] vertices = quad.vertices().stream().map(vertex ->
                    new ModelPart.Vertex(vertex.x() * 16, vertex.y() * 16, vertex.z() * 16,
                        vertex.u(), vertex.v())).toArray(ModelPart.Vertex[]::new);
                mesh.polygons[i] = new ModelPart.Polygon(vertices, new Vector3f(quad.normal()));
            }
            meshes.add(mesh); allQuads.addAll(quads);
            }
            Model model = new Model.Simple(new ModelPart(meshes, Map.of()), RenderType::armorCutoutNoCull);
            result.add(new Layer(slot, model, List.copyOf(allQuads)));
        }
        return List.copyOf(result);
    }

    static void render(List<Layer> layers, PoseStack stack, MultiBufferSource buffers, int light) {
        if (renderer == null) return;
        var player = Minecraft.getInstance().player;
        for (Layer layer : layers) {
            var item = player.getItemBySlot(layer.slot());
            var equippable = item.get(DataComponents.EQUIPPABLE);
            if (equippable == null || equippable.slot() != layer.slot() || equippable.assetId().isEmpty()) continue;
            var type = layer.slot() == EquipmentSlot.LEGS ? EquipmentClientInfo.LayerType.HUMANOID_LEGGINGS
                : EquipmentClientInfo.LayerType.HUMANOID;
            renderer.renderLayers(type, equippable.assetId().orElseThrow(), layer.model(), item,
                stack, buffers, light, player.getSkin().texture());
        }
    }
}
