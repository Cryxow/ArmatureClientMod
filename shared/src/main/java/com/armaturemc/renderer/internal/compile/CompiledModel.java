package com.armaturemc.renderer.internal.compile;

import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Packet- and resource-pack-independent hierarchy produced from one bbmodel. */
public record CompiledModel(String name, Map<String, Bone> bones, Map<String, Cube> cubes,
                            Map<String, Locator> locators, List<String> rootBones) {
    public CompiledModel {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Compiled model name is blank");
        bones = immutableOrdered(bones);
        cubes = immutableOrdered(cubes);
        locators = immutableOrdered(locators);
        rootBones = List.copyOf(rootBones);
    }

    private static <K, V> Map<K, V> immutableOrdered(Map<K, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    public CompiledModel(String name, Map<String, Bone> bones, Map<String, Cube> cubes,
                         List<String> rootBones) {
        this(name, bones, cubes, Map.of(), rootBones);
    }
    public record Vector(double x, double y, double z) {
        public Vector {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("Compiled model vector must be finite");
            }
        }
    }

    public record Bone(String id, String name, String parentId, Vector pivot, Vector rotation,
                       List<String> childBoneIds, List<String> cubeIds, boolean visible,
                       ArmatureBoneRole armatureRole, boolean armaturePhysics) {
        public Bone {
            if (id == null || id.isBlank() || name == null || name.isBlank()) {
                throw new IllegalArgumentException("Compiled bone id and name are required");
            }
            childBoneIds = List.copyOf(childBoneIds);
            cubeIds = List.copyOf(cubeIds);
        }

        /** Legacy constructors carry no explicit role; name routing remains bbmodel-only. */
        public Bone(String id, String name, String parentId, Vector pivot, Vector rotation,
                    List<String> childBoneIds, List<String> cubeIds, boolean visible) {
            this(id, name, parentId, pivot, rotation, childBoneIds, cubeIds, visible, null, false);
        }

        public Bone(String id, String name, String parentId, Vector pivot, Vector rotation,
                    List<String> childBoneIds, List<String> cubeIds) {
            this(id, name, parentId, pivot, rotation, childBoneIds, cubeIds, true);
        }
    }

    public record Cube(String id, String parentBoneId, Vector from, Vector to, Vector pivot,
                       Vector rotation, double inflate) {
        public Cube {
            if (id == null || id.isBlank() || parentBoneId == null || parentBoneId.isBlank()) {
                throw new IllegalArgumentException("Compiled cube id and parent are required");
            }
            if (!Double.isFinite(inflate)) throw new IllegalArgumentException("Cube inflate must be finite");
        }
    }

    /** Non-rendered Blockbench marker attached to one compiled bone. */
    public record Locator(String id, String name, String parentBoneId, Vector position,
                          Vector rotation, boolean visible) {
        public Locator {
            if (id == null || id.isBlank() || name == null || name.isBlank()
                || parentBoneId == null || parentBoneId.isBlank()) {
                throw new IllegalArgumentException("Compiled locator id, name and parent are required");
            }
        }
    }
}
