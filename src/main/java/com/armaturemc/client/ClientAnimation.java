package com.armaturemc.client;

import com.armaturemc.client.protocol.ClientProtocol;
import com.armaturemc.renderer.internal.animation.*;
import com.armaturemc.renderer.internal.bbmodel.NativeModelLimits;
import com.armaturemc.renderer.internal.compile.*;
import com.armaturemc.renderer.internal.pose.*;
import com.google.gson.*;
import java.util.*;
import org.joml.Matrix4f;
import com.armaturemc.renderer.internal.runtime.NativeMotionSampler;
import com.armaturemc.renderer.internal.runtime.NativeBonePhysics;
import com.armaturemc.renderer.api.ArmatureMotionSettings;
import com.armaturemc.renderer.api.RenderMotionInput;
import com.armaturemc.core.animation.AdditivePose;

/** Runs Armature's own keyframe, Molang, hierarchy and blend evaluators at render-frame time. */
final class ClientAnimation {
    private final CompiledModel hierarchy;
    private final NativePose identity;
    private final Map<String, NativeAnimation> clips;
    private final Map<String, String> parents = new HashMap<>();
    private final PoseEvaluator evaluator = new PoseEvaluator();
    private final NativeAnimationMixer mixer = new NativeAnimationMixer();
    private final HierarchyPoseComposer composer = new HierarchyPoseComposer();
    private final NativeAnimationClock clock = new NativeAnimationClock();
    private Map<String, AdditivePose> offsets = Map.of(), previousOffsets = Map.of();
    private long offsetsAt;
    private Matrix4f defaultBasis = new Matrix4f();
    private JsonObject control = new JsonObject();
    private long receivedAt, transitionId;
    private NativePoseTransition transition;
    private NativeMotionSampler motion;
    private long captureAt, sampledAt = Long.MIN_VALUE;
    private NativePose sampledPose;
    private NativeBonePhysics physics;
    private final Set<String> physicsBones = new HashSet<>();
    private String cameraBone;
    private org.joml.Quaternionf cameraRest;
    private JsonObject camera = new JsonObject();

    ClientAnimation(JsonObject document) {
        NativeModelLimits limits = new NativeModelLimits(ClientProtocol.MAX_MODEL, ClientProtocol.MAX_BONES,
            4096, 1024, 131072, 32, 64, ClientProtocol.MAX_MODEL, 2048, 2048);
        hierarchy = new BlockbenchHierarchyCompiler(limits).compile(document);
        Map<String, BonePose> rest = new HashMap<>();
        hierarchy.bones().keySet().forEach(id -> rest.put(id, BonePose.IDENTITY));
        identity = new NativePose(Map.copyOf(rest), Map.of());
        for (var group : document.getAsJsonArray("groups")) {
            var bone = group.getAsJsonObject();
            if (bone.has("clientPhysics") && bone.get("clientPhysics").getAsBoolean()) physicsBones.add(bone.get("uuid").getAsString());
            if (bone.has("clientCamera") && bone.get("clientCamera").getAsBoolean()) cameraBone = bone.get("uuid").getAsString();
        }
        Map<String, String> names = new HashMap<>();
        hierarchy.bones().forEach((id, bone) -> { names.put(bone.name(), id); if (bone.parentId() != null) parents.put(id, bone.parentId()); });
        Map<String, NativeAnimation> parsed = new HashMap<>();
        for (var clip : new BlockbenchAnimationParser(limits).parseWithDiagnostics(document,
            hierarchy.bones().keySet(), hierarchy.name(), names).animations()) parsed.put(clip.name(), clip);
        clips = Map.copyOf(parsed);
        if (cameraBone != null) cameraRest = localMatrices(new NativePose(Map.of(), Map.of())).get(cameraBone)
            .getUnnormalizedRotation(new org.joml.Quaternionf()).invert();
    }

    void configure(JsonObject options) {
        if (options.has("basis")) {
            var basis = options.getAsJsonArray("basis");
            if (basis.size() != 16) throw new IllegalArgumentException("Invalid basis");
            float[] values = new float[16];
            for (int i = 0; i < 16; i++) {
                values[i] = basis.get(i).getAsFloat();
                if (!Float.isFinite(values[i])) throw new IllegalArgumentException("Invalid basis");
            }
            defaultBasis = new Matrix4f().set(values);
        }
        camera = options.has("camera") ? options.getAsJsonObject("camera") : new JsonObject();
        if (!options.has("motion")) return;
        var settings = new Gson().fromJson(options.get("motion"), ArmatureMotionSettings.class);
        if (motion == null) { motion = new NativeMotionSampler(settings, () -> captureAt); motion.renderFrames(true); }
        else motion.settings(settings);
        motion.proceduralBob(!options.has("proceduralBob") || options.get("proceduralBob").getAsBoolean());
        if (physics == null) physics = new NativeBonePhysics(settings.physics()); else physics.settings(settings.physics());
    }

    ClientCameraEffect cameraEffect(long now, MolangContext viewer) {
        if (receivedAt == 0 || cameraBone == null || !camera.has("enabled") || !camera.get("enabled").getAsBoolean())
            return ClientCameraEffect.IDENTITY;
        var matrices = localMatrices(sample(now, viewer), offsets(now));
        var q = matrices.get(cameraBone).getUnnormalizedRotation(new org.joml.Quaternionf()).mul(cameraRest).normalize();
        if (motion != null) q = new Matrix4f(motion.matrix(true)).getUnnormalizedRotation(new org.joml.Quaternionf()).mul(q).normalize();
        if (q.w < 0) q.set(-q.x, -q.y, -q.z, -q.w);
        double angle = q.angle();
        double max = Math.toRadians(number(camera, "maximumDegrees", 7));
        if (camera.has("clamp") && camera.get("clamp").getAsBoolean() && angle > max && angle > 1e-6)
            q = new org.joml.Quaternionf().slerp(q, (float)(max / angle));
        // Shader reprojection needs a crop margin; rotating the actual camera does not.
        return new ClientCameraEffect(q);
    }

    void capture(NativeMotionSampler.Input input, long now) {
        captureAt = now;
        if (motion != null) motion.capture(input, RenderMotionInput.fallback(0, 0));
    }

    void resetPlayback() {
        control = new JsonObject(); receivedAt = transitionId = 0; transition = null;
        offsets = previousOffsets = Map.of(); offsetsAt = 0;
        // A different session must not inherit the last model's spring velocity.
        motion = null; physics = null;
        sampledAt = Long.MIN_VALUE; sampledPose = null;
    }

    void receive(ClientProtocol.Frame frame, long now, MolangContext viewer) {
        JsonObject previousControl = control;
        double previousElapsed = receivedAt == 0 ? 0 : Math.max(0, (now - receivedAt) / 1e9);
        JsonObject next = JsonParser.parseString(frame.control()).getAsJsonObject();
        validate(next);
        long nextTransition = next.has("transition") ? next.get("transition").getAsLong() : 0;
        if (nextTransition != transitionId) {
            // Start from the last displayed pose, with its real render-frame Molang context.
            NativePose previous = receivedAt == 0 || nextTransition == 0 ? null
                : sampledPose != null ? sampledPose : sample(now, viewer);
            if (nextTransition != 0) transition = previous == null ? null : new NativePoseTransition(previous,
                now, (long)(number(next, "transitionRemaining", 0) * 1e9));
            // Server completion can arrive before the local transition's last frame.
            // Its running fade must finish instead of snapping to the target.
            transitionId = nextTransition;
        }
        boolean first = receivedAt == 0;
        Map<String, AdditivePose> adjusted = new HashMap<>();
        for (var bone : frame.bones()) {
            if (!hierarchy.bones().containsKey(bone.id())) continue;
            float[] value = bone.offset();
            adjusted.put(bone.id(), new AdditivePose(value[0], value[1], value[2], value[3], value[4], value[5], value[6]));
        }
        if (!adjusted.equals(offsets)) {
            previousOffsets = first ? Map.copyOf(adjusted) : offsets(now);
            offsets = Map.copyOf(adjusted); offsetsAt = now;
        }
        control = next;
        // A server snapshot changes selection and additives, not the running local clock.
        // Preserve an unchanged epoch across jittery 20 Hz deliveries.
        for (String key : List.of("loop", "action")) {
            if (first || !previousControl.has(key) || !control.has(key)) continue;
            var before = previousControl.getAsJsonObject(key); var current = control.getAsJsonObject(key);
            if (Objects.equals(before.get("name"), current.get("name"))
                && Objects.equals(before.get("epoch"), current.get("epoch"))
                && number(before, "speed", 1) == number(current, "speed", 1)) {
                current.addProperty("elapsed", number(before, "elapsed", 0) + previousElapsed);
                // The first visible sample and pose-to-pose transitions already own
                // blend-in. Later 20 Hz snapshots cannot start a second rest-pose fade.
                if (before.has("crossfade") && before.get("crossfade").getAsBoolean())
                    current.addProperty("crossfade", true);
            }
        }
        receivedAt = now;
        sampledAt = Long.MIN_VALUE;
    }

    private Map<String, AdditivePose> offsets(long now) {
        double alpha = Math.clamp((now - offsetsAt) / 50_000_000.0, 0, 1);
        if (alpha >= 1 || previousOffsets.equals(offsets)) return offsets;
        Map<String, AdditivePose> result = new HashMap<>();
        Set<String> ids = new HashSet<>(previousOffsets.keySet()); ids.addAll(offsets.keySet());
        for (String id : ids) {
            var from = previousOffsets.getOrDefault(id, AdditivePose.identity());
            var to = offsets.getOrDefault(id, AdditivePose.identity());
            var q = new org.joml.Quaterniond(from.qx(), from.qy(), from.qz(), from.qw())
                .slerp(new org.joml.Quaterniond(to.qx(), to.qy(), to.qz(), to.qw()), alpha).normalize();
            result.put(id, new AdditivePose(from.x() + (to.x() - from.x()) * alpha,
                from.y() + (to.y() - from.y()) * alpha, from.z() + (to.z() - from.z()) * alpha,
                q.x, q.y, q.z, q.w));
        }
        return result;
    }

    Map<String, Matrix4f> matrices(long now, MolangContext viewer, List<ClientProtocol.Bone> visible) {
        NativePose pose = sample(now, viewer);
        Map<String, AdditivePose> physicsPose = new HashMap<>(offsets(now));
        if (physics != null && motion != null && !physicsBones.isEmpty()) {
            var frames = new HashMap<String, org.joml.Quaterniond>();
            hierarchy.bones().forEach((id, bone) -> {
                var local = pose.bones().getOrDefault(id, BonePose.IDENTITY).rotation();
                var frame = new org.joml.Quaterniond().rotationZYX(Math.toRadians(bone.rotation().z() + local.z()),
                    Math.toRadians(bone.rotation().y() + local.y()), Math.toRadians(bone.rotation().x() + local.x()));
                var offset = physicsPose.getOrDefault(id, AdditivePose.identity());
                frames.put(id, frame.mul(new org.joml.Quaterniond(offset.qx(), offset.qy(), offset.qz(), offset.qw())));
            });
            physics.update(physicsBones, parents, frames, motion.physicsSnapshot());
            physics.rotations(physicsBones).forEach((id, rotation) -> {
                var offset = physicsPose.getOrDefault(id, AdditivePose.identity());
                var q = new org.joml.Quaterniond(offset.qx(), offset.qy(), offset.qz(), offset.qw())
                    .mul(rotation.quaternion()).normalize();
                physicsPose.put(id, new AdditivePose(offset.x(), offset.y(), offset.z(), q.x, q.y, q.z, q.w));
            });
        }
        Map<String, Matrix4f> animated = localMatrices(pose, physicsPose);
        Map<String, Matrix4f> result = new HashMap<>();
        for (var bone : visible) {
            if (!bone.visible()) continue;
            Matrix4f local = animated.get(bone.id());
            if (local == null) continue;
            Matrix4f matrix = new Matrix4f(defaultBasis).mul(local);
            if (motion != null) matrix = new Matrix4f(motion.matrix(false)).mul(matrix);
            result.put(bone.id(), matrix);
        }
        return result;
    }

    String itemModel(boolean offhand) {
        var value = control.get(offhand ? "offItemModel" : "mainItemModel");
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private NativePose sample(long now, MolangContext viewer) {
        if (sampledAt != now) {
            sampledPose = pose(Math.max(0, (now - receivedAt) / 1e9), viewer, now);
            sampledAt = now;
        }
        return sampledPose;
    }

    private NativePose pose(double elapsed, MolangContext viewer, long now) {
        NativePose result = identity;
        var loop = layer("loop", elapsed, false);
        if (loop != null) result = mixer.blend(result, evaluator.evaluate(loop.clip.animation(), loop.clip.timeSeconds(),
            identity, parents, viewer.withClocks(loop.clip.timeSeconds(), viewer.deltaTime(), viewer.gameTime())), loop.weight);
        var action = layer("action", elapsed, true);
        if (action != null) {
            NativePose base = action.clip.animation().override() ? result : identity;
            result = mixer.blend(result, evaluator.evaluate(action.clip.animation(), action.clip.timeSeconds(),
                base, parents, viewer.withClocks(action.clip.timeSeconds(), viewer.deltaTime(), viewer.gameTime())), action.weight);
        }
        if (transition != null && transition.complete(now)) transition = null;
        return transition == null ? result : transition.sample(result, now);
    }

    private Layer layer(String key, double elapsed, boolean action) {
        if (!control.has(key)) return null;
        JsonObject state = control.getAsJsonObject(key); String name = state.get("name").getAsString();
        NativeAnimation animation = clips.get(name); if (animation == null) return null;
        animation = action ? animation.asActionPlayback() : animation.asLoopPlayback();
        float speed = (float)number(state, "speed", 1);
        var sample = clock.sample(animation, number(state, "elapsed", 0) + elapsed, speed);
        var clip = new NativeAnimationPlayback.Clip(name, animation, sample.timeSeconds(), sample.complete(), sample.authoredSeconds(), sample.loopCount());
        boolean crossfade = state.has("crossfade") && state.get("crossfade").getAsBoolean();
        int in = (int)number(state, "in", 0), out = (int)number(state, "out", 0);
        double weight = action ? NativeAnimationPlayback.actionWeight(clip, speed, in, out, crossfade)
            : NativeAnimationPlayback.loopWeight(clip, speed, in, out, crossfade);
        return new Layer(clip, weight);
    }

    private Map<String, Matrix4f> localMatrices(NativePose pose) {
        return localMatrices(pose, Map.of());
    }
    private Map<String, Matrix4f> localMatrices(NativePose pose, Map<String, AdditivePose> additives) {
        Map<String, Matrix4f> result = new HashMap<>();
        composer.compose(hierarchy, pose, additives).forEach((id, bone) -> {
            var matrix = new Matrix4f(bone.matrix());
            matrix.m30(matrix.m30() / 16).m31(matrix.m31() / 16).m32(matrix.m32() / 16);
            result.put(id, matrix);
        });
        return result;
    }

    private static void validate(JsonObject root) {
        for (String name : List.of("loop", "action")) {
            if (!root.has(name)) continue;
            JsonObject state = root.getAsJsonObject(name);
            double speed = number(state, "speed", 1), elapsed = number(state, "elapsed", 0);
            if (speed == 0 || Math.abs(speed) > 65536 || elapsed < 0) throw new IllegalArgumentException("Invalid playback clock");
            for (String blend : List.of("in", "out")) if (number(state, blend, 0) < 0) throw new IllegalArgumentException("Invalid blend");
        }
        if (number(root, "transitionRemaining", 0) < 0) throw new IllegalArgumentException("Invalid transition");
    }
    private static double number(JsonObject object, String key, double fallback) {
        double value = object.has(key) ? object.get(key).getAsDouble() : fallback;
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite animation control"); return value;
    }
    private record Layer(NativeAnimationPlayback.Clip clip, double weight) { }
}
