package com.armaturemc.renderer.internal.asset;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;

/** Native-only source contract. Legacy bbmodel sources remain supported. */
public final class NativeModelSource {
    private NativeModelSource() { }

    public static boolean supported(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".bbmodel") || name.endsWith(".armature");
    }

    public static String modelId(Path path) {
        String name = path.getFileName().toString();
        return name.substring(0, name.lastIndexOf('.'));
    }

    public static void validate(Path path, JsonElement source) {
        if (!path.getFileName().toString().endsWith(".armature")) return;
        if (!source.isJsonObject()) throw new IllegalArgumentException("Armature root must be an object");
        JsonObject root = source.getAsJsonObject();
        JsonObject contract = root.has("armature") && root.get("armature").isJsonObject()
            ? root.getAsJsonObject("armature") : new JsonObject();
        JsonObject meta = root.has("meta") && root.get("meta").isJsonObject()
            ? root.getAsJsonObject("meta") : new JsonObject();
        if (!contract.has("format_version") || !contract.get("format_version").isJsonPrimitive()
            || !contract.getAsJsonPrimitive("format_version").isNumber()
            || contract.get("format_version").getAsBigDecimal().compareTo(java.math.BigDecimal.valueOf(2)) != 0
            || !"native".equals(value(contract, "renderer"))
            || !"armature_fpv".equals(value(meta, "model_format"))) {
            throw new IllegalArgumentException("Unsupported .armature document; expected native Armature format version 2; version 1 requires manual recreation");
        }
        validateRoles(root);
    }

    public static boolean isArmature(JsonObject root) {
        return root.has("meta") && root.get("meta").isJsonObject()
            && "armature_fpv".equals(value(root.getAsJsonObject("meta"), "model_format"));
    }

    public static void validateDocument(JsonObject root) {
        if (isArmature(root)) validate(Path.of("document.armature"), root);
    }

    private static void validateRoles(JsonObject root) {
        if (!root.has("groups") || !root.get("groups").isJsonArray()) {
            throw new IllegalArgumentException("Armature document is missing groups");
        }
        int cameras = 0;
        for (JsonElement entry : root.getAsJsonArray("groups")) {
            if (!entry.isJsonObject()) throw new IllegalArgumentException("Armature groups must contain objects");
            JsonObject group = entry.getAsJsonObject();
            String id = value(group, "uuid");
            if (value(group, "name").startsWith("__armature_")) {
                throw new IllegalArgumentException("Reserved compiler bone name on " + id);
            }
            if (group.has("armature_role")) {
                JsonElement roleValue = group.get("armature_role");
                if (!roleValue.isJsonPrimitive() || !roleValue.getAsJsonPrimitive().isString()) {
                    throw new IllegalArgumentException("armature_role must be a string on bone " + id);
                }
                String role = roleValue.getAsString();
                var parsed = com.armaturemc.renderer.internal.compile.ArmatureBoneRole.parse(role);
                if (!role.equals(parsed.serialized()) || parsed == com.armaturemc.renderer.internal.compile.ArmatureBoneRole.CAMERA_MARKER) {
                    throw new IllegalArgumentException("Invalid armature_role '" + role + "' on bone " + id);
                }
                if (parsed == com.armaturemc.renderer.internal.compile.ArmatureBoneRole.CAMERA) cameras++;
            }
            if (group.has("armature_physics") && (!group.get("armature_physics").isJsonPrimitive()
                || !group.getAsJsonPrimitive("armature_physics").isBoolean())) {
                throw new IllegalArgumentException("armature_physics must be a boolean on bone " + id);
            }
        }
        if (cameras > 1) throw new IllegalArgumentException("Only one Armature camera role is allowed");
    }

    private static String value(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }
}
