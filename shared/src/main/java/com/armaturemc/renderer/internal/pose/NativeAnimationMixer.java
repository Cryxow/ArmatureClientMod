package com.armaturemc.renderer.internal.pose;

import com.armaturemc.renderer.internal.animation.Vector3;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Blends one authored layer over a lower-priority pose without depending on
 * Bukkit, JOML, or packet transport.
 */
public final class NativeAnimationMixer {
    public NativePose blend(NativePose lower, NativePose upper, double weight) {
        if (lower == null || upper == null) throw new IllegalArgumentException("Poses are required");
        if (!Double.isFinite(weight)) throw new IllegalArgumentException("Blend weight must be finite");
        double amount = Math.max(0.0, Math.min(1.0, weight));
        Map<String, BonePose> bones = new LinkedHashMap<>();
        bones.putAll(lower.bones() == null ? Map.of() : lower.bones());
        (upper.bones() == null ? Map.<String, BonePose>of() : upper.bones()).forEach((id, value) ->
            bones.put(id, blend(bones.getOrDefault(id, BonePose.IDENTITY), value, amount)));

        Map<String, Boolean> visibility = new LinkedHashMap<>();
        visibility.putAll(lower.visibility() == null ? Map.of() : lower.visibility());
        if (amount > 0.0) visibility.putAll(upper.visibility() == null ? Map.of() : upper.visibility());
        return new NativePose(Map.copyOf(bones), Map.copyOf(visibility));
    }

    private static BonePose blend(BonePose lower, BonePose upper, double weight) {
        return new BonePose(Vector3.lerp(lower.position(), upper.position(), weight),
            blendAngles(lower.rotation(), upper.rotation(), weight),
            Vector3.lerp(lower.scale(), upper.scale(), weight));
    }

    /** Interpolates Euler layers in authored degrees, preserving intentional full turns. */
    private static Vector3 blendAngles(Vector3 lower, Vector3 upper, double weight) {
        return Vector3.lerp(lower, upper, weight);
    }
}
