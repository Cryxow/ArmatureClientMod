package com.armaturemc.renderer.internal.compile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.armaturemc.renderer.internal.bbmodel.NativeModelLimits;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

/** Compiles only the group/cube outliner hierarchy; animation compilation is separate. */
public final class BlockbenchHierarchyCompiler {
    private final NativeModelLimits limits;

    public BlockbenchHierarchyCompiler() {
        this(NativeModelLimits.defaults());
    }

    public BlockbenchHierarchyCompiler(NativeModelLimits limits) {
        this.limits = java.util.Objects.requireNonNull(limits, "limits");
    }

    public CompiledModel compile(Path source) throws IOException {
        try (Reader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            return compile(JsonParser.parseReader(reader));
        }
    }

    public CompiledModel compile(JsonElement root) {
        if (!root.isJsonObject()) throw new IllegalArgumentException("Blockbench root must be an object.");
        JsonObject model = root.getAsJsonObject();
        Map<String, JsonObject> groups = indexed(model, "groups");
        boolean explicit = com.armaturemc.renderer.internal.asset.NativeModelSource.isArmature(model);
        Map<String, JsonObject> cubes = cubes(model);
        Map<String, JsonObject> locators = locators(model);
        if (groups.size() > limits.maxGroups()) {
            throw new IllegalArgumentException("Native model exceeds group limit of " + limits.maxGroups());
        }
        if (cubes.size() > limits.maxCubes()) {
            throw new IllegalArgumentException("Native model exceeds cube limit of " + limits.maxCubes());
        }
        validateElementUuids(model, groups.keySet());
        Set<String> ignored = ignoredElements(model);
        Map<String, MutableBone> bones = new LinkedHashMap<>();
        Map<String, CompiledModel.Cube> compiledCubes = new LinkedHashMap<>();
        Map<String, CompiledModel.Locator> compiledLocators = new LinkedHashMap<>();
        List<String> roots = new ArrayList<>();
        JsonArray outliner = requiredArray(model, "outliner");
        Set<String> visiting = new LinkedHashSet<>();
        Set<String> visited = new LinkedHashSet<>();
        for (JsonElement node : outliner) walk(node, null, groups, cubes, locators, ignored, bones,
            compiledCubes, compiledLocators, roots, 1, limits.maxHierarchyDepth(), visiting, visited);

        if (bones.isEmpty()) throw new IllegalArgumentException("Blockbench model has no outliner groups.");
        if (bones.size() != groups.size()) {
            throw new IllegalArgumentException("Blockbench outliner does not reference every group.");
        }
        if (compiledCubes.size() != cubes.size()) {
            throw new IllegalArgumentException("Blockbench outliner does not reference every cube.");
        }
        if (compiledLocators.size() != locators.size()) {
            throw new IllegalArgumentException("Blockbench outliner does not reference every locator.");
        }
        Map<String, CompiledModel.Bone> completed = new LinkedHashMap<>();
        bones.forEach((id, bone) -> {
            JsonObject group = groups.get(id);
            ArmatureBoneRole role = explicit
                ? ArmatureBoneRole.parse(string(group, "armature_role", "none")) : null;
            boolean physics = explicit && group.has("armature_physics") && group.get("armature_physics").getAsBoolean();
            completed.put(id, bone.freeze(role, physics));
        });
        return new CompiledModel(string(model, "name", "unnamed"), Map.copyOf(completed),
            Map.copyOf(compiledCubes), Map.copyOf(compiledLocators), List.copyOf(roots));
    }

    private static void walk(JsonElement node, String parentId, Map<String, JsonObject> groups,
                             Map<String, JsonObject> cubes, Map<String, JsonObject> locators, Set<String> ignored,
                             Map<String, MutableBone> bones, Map<String, CompiledModel.Cube> compiledCubes,
                             Map<String, CompiledModel.Locator> compiledLocators, List<String> roots,
                             int depth, int maxDepth, Set<String> visiting, Set<String> visited) {
        if (depth > maxDepth) throw new IllegalArgumentException("Blockbench hierarchy exceeds maximum depth");
        String id;
        JsonArray children = null;
        if (node.isJsonPrimitive()) {
            id = node.getAsString();
        } else if (node.isJsonObject()) {
            JsonObject value = node.getAsJsonObject();
            id = string(value, "uuid", "");
            if (value.has("children")) children = requiredArray(value, "children");
        } else {
            throw new IllegalArgumentException("Outliner node must be a UUID or object.");
        }
        if (id.isBlank()) throw new IllegalArgumentException("Outliner node has no UUID.");
        JsonObject group = groups.get(id);
        if (group != null) {
            if (visiting.contains(id)) throw new IllegalArgumentException("Blockbench outliner contains a cycle at " + id);
            if (visited.contains(id)) throw new IllegalArgumentException("Outliner group appears twice: " + id);
            visiting.add(id);
            String name = string(group, "name", "");
            if (name.isBlank()) throw new IllegalArgumentException("Group '" + id + "' has no exported name");
            MutableBone bone = new MutableBone(id, name, parentId,
                vector(group, "origin"), rendererRotation(group),
                !group.has("visibility") || group.get("visibility").getAsBoolean());
            bones.put(id, bone);
            if (parentId == null) roots.add(id); else bones.get(parentId).children.add(id);
            if (children != null) for (JsonElement child : children) {
                walk(child, id, groups, cubes, locators, ignored, bones, compiledCubes, compiledLocators,
                    roots, depth + 1, maxDepth, visiting, visited);
            }
            visiting.remove(id);
            visited.add(id);
            return;
        }
        JsonObject locator = locators.get(id);
        if (locator != null) {
            if (parentId == null) throw new IllegalArgumentException("Locator '" + id + "' has no parent group.");
            String name = string(locator, "name", id);
            if (compiledLocators.values().stream().anyMatch(value -> value.name().equals(name))) {
                throw new IllegalArgumentException("Duplicate locator name: " + name);
            }
            compiledLocators.put(id, new CompiledModel.Locator(id, name, parentId,
                vector(locator, "position"), optionalVector(locator, "rotation"),
                !locator.has("visibility") || locator.get("visibility").getAsBoolean()));
            return;
        }
        if (ignored.contains(id)) return;
        JsonObject cube = cubes.get(id);
        if (cube == null) throw new IllegalArgumentException("Outliner references unknown UUID: " + id);
        if (parentId == null) throw new IllegalArgumentException("Cube '" + id + "' has no parent group.");
        if (compiledCubes.containsKey(id)) throw new IllegalArgumentException("Cube appears twice: " + id);
        CompiledModel.Cube compiled = new CompiledModel.Cube(id, parentId, vector(cube, "from"),
            vector(cube, "to"), vector(cube, "origin"),
            optionalVector(cube, "rotation"), number(cube, "inflate", 0.0));
        compiledCubes.put(id, compiled);
        bones.get(parentId).cubes.add(id);
    }

    private static Map<String, JsonObject> indexed(JsonObject model, String field) {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        Set<String> names = new LinkedHashSet<>();
        for (JsonElement element : requiredArray(model, field)) {
            if (!element.isJsonObject()) throw new IllegalArgumentException(field + " must contain objects.");
            JsonObject value = element.getAsJsonObject();
            String id = string(value, "uuid", "");
            if (id.isBlank() || result.putIfAbsent(id, value) != null) {
                throw new IllegalArgumentException(field + " has missing or duplicate UUID.");
            }
            if ("groups".equals(field)) {
                String name = string(value, "name", "");
                if (name.isBlank() || !names.add(name)) {
                    throw new IllegalArgumentException("groups has missing or duplicate exported name: " + name);
                }
            }
        }
        return result;
    }

    private static void validateElementUuids(JsonObject model, Set<String> groupIds) {
        Set<String> ids = new LinkedHashSet<>(groupIds);
        for (JsonElement element : requiredArray(model, "elements")) {
            if (!element.isJsonObject()) continue;
            String id = string(element.getAsJsonObject(), "uuid", "");
            if (!id.isBlank() && !ids.add(id)) throw new IllegalArgumentException(
                "Blockbench groups/elements have duplicate UUID: " + id);
        }
    }

    private static Map<String, JsonObject> cubes(JsonObject model) {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        for (JsonElement element : requiredArray(model, "elements")) {
            if (!element.isJsonObject()) continue;
            JsonObject value = element.getAsJsonObject();
            if (!"cube".equals(string(value, "type", "cube"))) continue;
            String id = string(value, "uuid", "");
            if (id.isBlank() || result.putIfAbsent(id, value) != null) {
                throw new IllegalArgumentException("elements has missing or duplicate cube UUID.");
            }
        }
        return result;
    }

    private static Map<String, JsonObject> locators(JsonObject model) {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        for (JsonElement element : requiredArray(model, "elements")) {
            if (!element.isJsonObject()) continue;
            JsonObject value = element.getAsJsonObject();
            if (!"locator".equals(string(value, "type", "cube"))) continue;
            String id = string(value, "uuid", "");
            if (id.isBlank() || result.putIfAbsent(id, value) != null) {
                throw new IllegalArgumentException("elements has missing or duplicate locator UUID.");
            }
        }
        return result;
    }

    private static Set<String> ignoredElements(JsonObject model) {
        Set<String> ignored = new LinkedHashSet<>();
        for (JsonElement element : requiredArray(model, "elements")) {
            if (!element.isJsonObject()) continue;
            JsonObject value = element.getAsJsonObject();
            String type = string(value, "type", "cube");
            if ("cube".equals(type)) continue;
            if ("locator".equals(type)) continue;
            String id = string(value, "uuid", "");
            if ("camera".equals(type) || "null_object".equals(type)) {
                if (!id.isBlank()) ignored.add(id);
                continue;
            }
            throw new IllegalArgumentException("Unsupported Blockbench element type: " + type);
        }
        return ignored;
    }

    private static JsonArray requiredArray(JsonObject object, String field) {
        if (!object.has(field) || !object.get(field).isJsonArray()) {
            throw new IllegalArgumentException("Missing array '" + field + "'.");
        }
        return object.getAsJsonArray(field);
    }

    private static CompiledModel.Vector vector(JsonObject object, String field) {
        JsonArray values = requiredArray(object, field);
        if (values.size() != 3) throw new IllegalArgumentException("Invalid vector '" + field + "'.");
        try {
            double x = values.get(0).getAsDouble();
            double y = values.get(1).getAsDouble();
            double z = values.get(2).getAsDouble();
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new NumberFormatException();
            return new CompiledModel.Vector(x, y, z);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Non-numeric vector '" + field + "'.", exception);
        }
    }

    private static CompiledModel.Vector optionalVector(JsonObject object, String field) {
        return object.has(field) ? vector(object, field) : new CompiledModel.Vector(0.0, 0.0, 0.0);
    }

    /**
     * BetterModel's Blockbench importer mirrors group rotations when it builds
     * the renderer blueprint (ModelOutliner uses rotation().invertXZ()). Keep
     * the source JSON untouched and apply that conversion at the native
     * compiler boundary, just like the existing renderer-origin conversion.
     */
    private static CompiledModel.Vector rendererRotation(JsonObject group) {
        CompiledModel.Vector rotation = optionalVector(group, "rotation");
        return new CompiledModel.Vector(-rotation.x(), rotation.y(), -rotation.z());
    }

    private static double number(JsonObject object, String field, double fallback) {
        if (!object.has(field)) return fallback;
        double value = object.get(field).getAsDouble();
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite '" + field + "'.");
        return value;
    }

    private static String string(JsonObject object, String field, String fallback) {
        return object.has(field) && object.get(field).isJsonPrimitive()
            ? object.get(field).getAsString() : fallback;
    }

    private static final class MutableBone {
        private final String id;
        private final String name;
        private final String parent;
        private final CompiledModel.Vector pivot;
        private final CompiledModel.Vector rotation;
        private final boolean visible;
        private final List<String> children = new ArrayList<>();
        private final List<String> cubes = new ArrayList<>();

        private MutableBone(String id, String name, String parent, CompiledModel.Vector pivot,
                            CompiledModel.Vector rotation, boolean visible) {
            this.id = id; this.name = name; this.parent = parent; this.pivot = pivot; this.rotation = rotation;
            this.visible = visible;
        }

        private CompiledModel.Bone freeze(ArmatureBoneRole role, boolean physics) {
            return new CompiledModel.Bone(id, name, parent, pivot, rotation, List.copyOf(children), List.copyOf(cubes), visible, role, physics);
        }
    }
}
