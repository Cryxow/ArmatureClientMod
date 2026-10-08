package com.armaturemc.renderer.internal.pose;

import com.armaturemc.renderer.internal.animation.MolangContext;
import com.armaturemc.renderer.internal.animation.NativeAnimation;
import com.armaturemc.renderer.internal.animation.TimelineCommand;
import com.armaturemc.renderer.internal.animation.Vector3;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Evaluates local numeric tracks and applies visibility commands without Bukkit state. */
public final class PoseEvaluator {
    public NativePose evaluate(NativeAnimation animation, double time, Map<String, Boolean> previousVisibility) {
        return evaluate(animation, time, new NativePose(Map.of(), previousVisibility), Map.of());
    }

    /**
     * Samples an animation over an existing pose.  Blockbench animation
     * channels are sparse: an action which only keys an arm must not reset the
     * rest of the active loop to identity.  This is the native equivalent of
     * BetterModel's hierarchical override behaviour.
     */
    public NativePose evaluate(NativeAnimation animation, double time, NativePose base) {
        return evaluate(animation, time, base, Map.of());
    }

    /** Evaluates with model parent ids so override subtrees remain explicit. */
    public NativePose evaluate(NativeAnimation animation, double time, NativePose base,
                               Map<String, String> parentIds) {
        return evaluate(animation, time, base, parentIds, null);
    }

    /**
     * Evaluates against the viewer's live Molang state, so keyframes authored as
     * expressions resolve for this frame. A null context samples the compile-time
     * constants, which is what pose-regression callers want.
     */
    public NativePose evaluate(NativeAnimation animation, double time, NativePose base,
                               Map<String, String> parentIds, MolangContext context) {
        Map<String, String> parents = parentIds == null ? Map.of() : parentIds;
        Map<String, BonePose> bones = new LinkedHashMap<>();
        if (base != null && base.bones() != null) bones.putAll(base.bones());
        Set<String> overrideRoots = animation.override() ? overrideRoots(animation, parents) : Set.of();
        for (String id : new HashSet<>(bones.keySet())) {
            if (overrideRoots.stream().anyMatch(root -> isInSubtree(id, root, parents))) {
                bones.put(id, BonePose.IDENTITY);
            }
        }
        animation.bones().forEach((id, tracks) -> {
            BonePose previous = bones.getOrDefault(id, BonePose.IDENTITY);
            Vector3 position = tracks.position() == null ? previous.position()
                : previous.position().add(tracks.position().sample(time, context));
            Vector3 rotation = tracks.rotation() == null ? previous.rotation()
                : previous.rotation().add(tracks.rotation().sampleAngles(time, context));
            Vector3 scale = tracks.scale() == null ? previous.scale()
                : multiplyScale(previous.scale(), tracks.scale().sample(time, context));
            bones.put(id, new BonePose(
                position, rotation, scale));
        });
        Map<String, Boolean> visibility = new LinkedHashMap<>();
        if (base != null && base.visibility() != null) visibility.putAll(base.visibility());
        animation.bones().keySet().forEach(id -> visibility.putIfAbsent(id, true));
        return new NativePose(Map.copyOf(bones), Map.copyOf(visibility));
    }

    /** BetterModel stores scale keyframes as offsets from one, then multiplies layers. */
    private static Vector3 multiplyScale(Vector3 base, Vector3 offset) {
        return new Vector3(base.x() * (1.0 + offset.x()), base.y() * (1.0 + offset.y()),
            base.z() * (1.0 + offset.z()));
    }

    private static Set<String> overrideRoots(NativeAnimation animation, Map<String, String> parentIds) {
        Set<String> roots = new HashSet<>(animation.bones().keySet());
        animation.bones().keySet().forEach(id -> {
            String parent = parentIds.get(id);
            Set<String> visited = new HashSet<>();
            while (parent != null && visited.add(parent)) {
                if (animation.bones().containsKey(parent)) {
                    roots.remove(id);
                    break;
                }
                parent = parentIds.get(parent);
            }
        });
        return Set.copyOf(roots);
    }

    private static boolean isInSubtree(String id, String root, Map<String, String> parentIds) {
        Set<String> visited = new HashSet<>();
        String current = id;
        while (current != null && visited.add(current)) {
            if (root.equals(current)) return true;
            current = parentIds.get(current);
        }
        return false;
    }

    public Map<String, Boolean> applyTimeline(Map<String, Boolean> visibility,
                                               Iterable<TimelineCommand> commands) {
        Map<String, Boolean> updated = new LinkedHashMap<>();
        if (visibility != null) updated.putAll(visibility);
        for (TimelineCommand command : commands) {
            if (command instanceof TimelineCommand.PartVisibility part) {
                updated.put(part.bone(), part.visible());
            }
        }
        return Map.copyOf(updated);
    }
}
