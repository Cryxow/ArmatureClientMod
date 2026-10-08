package com.armaturemc.renderer.internal.pose;

import com.armaturemc.core.animation.AdditivePose;
import com.armaturemc.renderer.internal.animation.Vector3;
import com.armaturemc.renderer.internal.compile.CompiledModel;
import java.util.LinkedHashMap;
import java.util.Map;
import org.joml.Matrix4d;
import org.joml.Quaterniond;

/** Applies local bone pose to the compiled outliner hierarchy. */
public final class HierarchyPoseComposer {
    public Map<String, WorldBonePose> compose(CompiledModel model, NativePose pose) {
        return compose(model, pose, Map.of());
    }

    public Map<String, WorldBonePose> compose(CompiledModel model, NativePose pose,
                                               Map<String, AdditivePose> additives) {
        Map<String, WorldBonePose> result = new LinkedHashMap<>();
        Map<String, AdditivePose> actualAdditives = additives == null ? Map.of() : additives;
        for (String root : model.rootBones()) composeBone(root, new Matrix4d(), null, model, pose,
            actualAdditives, result);
        return Map.copyOf(result);
    }

    private static void composeBone(String id, Matrix4d parent, CompiledModel.Vector parentPivot,
                                    CompiledModel model, NativePose pose,
                                    Map<String, AdditivePose> additives,
                                    Map<String, WorldBonePose> result) {
        CompiledModel.Bone bone = model.bones().get(id);
        if (bone == null) throw new IllegalArgumentException("Compiled model misses bone " + id);
        BonePose local = pose.bones().getOrDefault(id, BonePose.IDENTITY);
        AdditivePose additive = additives.getOrDefault(id, AdditivePose.identity());
        Matrix4d matrix = new Matrix4d(parent).mul(localMatrix(bone.pivot(), parentPivot,
            bone.rotation(), local, additive));
        boolean visible = bone.visible() && pose.visibility().getOrDefault(id, true)
            && (bone.parentId() == null || result.get(bone.parentId()).visible());
        result.put(id, new WorldBonePose(matrix, visible));
        for (String child : bone.childBoneIds()) composeBone(child, matrix, bone.pivot(), model, pose,
            additives, result);
    }

    /** Mirrors BetterModel RendererGroup: inverted X/Z origin, parent-relative position, ZYX rotation. */
    private static Matrix4d localMatrix(CompiledModel.Vector pivot, CompiledModel.Vector parentPivot,
                                        CompiledModel.Vector defaultRotation,
                                        BonePose pose, AdditivePose additive) {
        Vector3 position = pose.position();
        Vector3 rotation = pose.rotation();
        Vector3 scale = pose.scale();
        double parentX = parentPivot == null ? 0.0 : parentPivot.x();
        double parentY = parentPivot == null ? 0.0 : parentPivot.y();
        double parentZ = parentPivot == null ? 0.0 : parentPivot.z();
        return new Matrix4d().translate(-(pivot.x() - parentX), pivot.y() - parentY,
                -(pivot.z() - parentZ))
            .translate(position.x() + additive.x() * 16.0,
                position.y() + additive.y() * 16.0,
                position.z() + additive.z() * 16.0)
            // BetterModel rebuilds the bone quaternion from the authored raw
            // Euler rotation plus the animated Euler delta. Multiplying two
            // quaternions here is not equivalent for multi-axis rotations and
            // causes visible orientation drift (notably on first-person rigs).
            .rotateZYX(Math.toRadians(defaultRotation.z() + rotation.z()),
                Math.toRadians(defaultRotation.y() + rotation.y()),
                Math.toRadians(defaultRotation.x() + rotation.x()))
            .rotate(new Quaterniond(additive.qx(), additive.qy(), additive.qz(), additive.qw()))
            .scale(scale.x(), scale.y(), scale.z());
    }
}
