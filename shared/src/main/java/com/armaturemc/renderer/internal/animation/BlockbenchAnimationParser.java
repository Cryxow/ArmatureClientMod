package com.armaturemc.renderer.internal.animation;

import com.armaturemc.renderer.internal.bbmodel.NativeModelLimits;
import com.armaturemc.renderer.internal.diagnostics.ModelDiagnostic;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.Set;

/**
 * Converts numeric Blockbench animator channels into native immutable tracks.
 * Supports Molang expressions in keyframe data points when evaluated with a MolangContext.
 */
public final class BlockbenchAnimationParser {
    private final NativeModelLimits limits;

    public BlockbenchAnimationParser() {
        this(NativeModelLimits.defaults());
    }

    public BlockbenchAnimationParser(NativeModelLimits limits) {
        this.limits = java.util.Objects.requireNonNull(limits, "limits");
    }

    public List<NativeAnimation> parse(JsonObject model) {
        return parseWithDiagnostics(model, Set.of(), "unknown").animations();
    }

    public ParseResult parseWithDiagnostics(JsonObject model, Set<String> knownBoneIds, String modelId) {
        return parseWithDiagnostics(model, knownBoneIds, modelId, MolangContext.defaults());
    }

    /**
     * Parses with bone-name resolution available: Blockbench keys animators by
     * UUID while BetterModel resolves them by the animator's {@code name}, so a
     * clip written against bone names must still land on the bone it names.
     */
    public ParseResult parseWithDiagnostics(JsonObject model, Set<String> knownBoneIds, String modelId,
                                            Map<String, String> boneIdsByName) {
        return parseWithDiagnostics(model, knownBoneIds, modelId, MolangContext.defaults(),
            boneIdsByName);
    }

    /**
     * Parses animations with Molang expression support.
     * @param model The Blockbench model JSON
     * @param knownBoneIds Set of known bone IDs
     * @param modelId The model ID
     * @param molangContext The Molang evaluation context
     */
    public ParseResult parseWithDiagnostics(JsonObject model, Set<String> knownBoneIds, String modelId,
                                          MolangContext molangContext) {
        return parseWithDiagnostics(model, knownBoneIds, modelId, molangContext, Map.of());
    }

    /** Parses animations with Molang expression support and bone-name resolution. */
    public ParseResult parseWithDiagnostics(JsonObject model, Set<String> knownBoneIds, String modelId,
                                          MolangContext molangContext,
                                          Map<String, String> boneIdsByName) {
        if (!model.has("animations")) return new ParseResult(List.of(), List.of());
        JsonArray source = array(model, "animations");
        if (source.size() > limits.maxAnimations()) {
            throw new IllegalArgumentException("Native model exceeds animation limit of " + limits.maxAnimations());
        }
        List<NativeAnimation> result = new ArrayList<>();
        List<ModelDiagnostic> diagnostics = new ArrayList<>();
        CoordinateSystem coordinateSystem = CoordinateSystem.from(model);
        int keyframeCount = 0;
        for (JsonElement entry : source) {
            if (!entry.isJsonObject()) throw new IllegalArgumentException("Animation must be an object.");
            ParseAnimation parsed = parseAnimation(entry.getAsJsonObject(), knownBoneIds, modelId, diagnostics,
                keyframeCount, coordinateSystem, molangContext, boneIdsByName);
            keyframeCount = parsed.keyframeCount();
            if (keyframeCount > limits.maxKeyframes()) {
                throw new IllegalArgumentException("Native model exceeds keyframe limit of " + limits.maxKeyframes());
            }
            result.add(parsed.animation());
        }
        return new ParseResult(List.copyOf(result), List.copyOf(diagnostics));
    }

    private ParseAnimation parseAnimation(JsonObject animation, Set<String> knownBoneIds, String modelId,
                                          List<ModelDiagnostic> diagnostics, int keyframeCount,
                                          CoordinateSystem coordinateSystem, MolangContext molangContext,
                                          Map<String, String> boneIdsByName) {
        String name = string(animation, "name");
        double length = number(animation, "length");
        String loop = animation.has("loop") ? string(animation, "loop") : "once";
        if (!Set.of("loop", "once", "hold").contains(loop)) {
            throw new IllegalArgumentException("Unsupported Blockbench loop mode '" + loop + "'.");
        }
        boolean override = animation.has("override") && animation.get("override").getAsBoolean();
        Map<String, NativeAnimation.TransformTracks> bones = new LinkedHashMap<>();
        double latestTransformTime = length;
        List<NativeAnimation.TimelineEvent> timeline = new ArrayList<>();
        JsonObject animators = object(animation, "animators");
        for (Map.Entry<String, JsonElement> entry : animators.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject animator = entry.getValue().getAsJsonObject();
            String type = string(animator, "type");
            if ("effect".equals(type)) {
                keyframeCount += parseTimeline(animator, timeline, diagnostics, modelId, name, length);
                continue;
            }
            // BetterModel has no animator-type branch at all: it resolves the
            // animator by its "name" and keys the bone tracks from it, so a
            // Blockbench camera animator naming a bone still moves that bone.
            // Resolve the same way here instead of dropping the animator.
            String target;
            if ("camera".equals(type)) {
                target = boneIdsByName.get(animatorName(animator, entry.getKey()));
                if (target == null) continue;
            } else if ("bone".equals(type)) {
                target = entry.getKey();
                if (!knownBoneIds.isEmpty() && !knownBoneIds.contains(target)) {
                    String named = boneIdsByName.get(animatorName(animator, entry.getKey()));
                    if (named != null) target = named;
                }
            } else {
                throw new IllegalArgumentException("Unsupported Blockbench animator type '" + type + "'.");
            }
            if (animator.has("rotation_global") && animator.get("rotation_global").getAsBoolean()) {
                throw new IllegalArgumentException("Global rotation is unsupported for bone " + target);
            }
            if (animator.has("quaternion_interpolation") && animator.get("quaternion_interpolation").getAsBoolean()) {
                throw new IllegalArgumentException("Quaternion interpolation is unsupported for bone " + target);
            }
            if (!knownBoneIds.isEmpty() && !knownBoneIds.contains(target)) {
                diagnostics.add(ModelDiagnostic.warning(modelId, "ANIMATION_TARGET_MISSING",
                    "Animation '" + name + "' targets absent bone UUID '" + target + "'."));
            }
            Map<String, List<AnimationKeyframe>> channels = new LinkedHashMap<>();
            if (animator.has("keyframes")) for (JsonElement frame : array(animator, "keyframes")) {
                if (!frame.isJsonObject()) throw new IllegalArgumentException("Keyframe must be an object.");
                JsonObject keyframe = frame.getAsJsonObject();
                String channel = string(keyframe, "channel");
                if (!List.of("position", "rotation", "scale").contains(channel)) {
                    throw new IllegalArgumentException("Unsupported bone animation channel '" + channel + "'.");
                }
                AnimationKeyframe parsedFrame = parseFrame(keyframe, length, channel, coordinateSystem,
                    diagnostics, modelId, name, target, molangContext);
                channels.computeIfAbsent(channel, ignored -> new ArrayList<>()).add(parsedFrame);
                latestTransformTime = Math.max(latestTransformTime, parsedFrame.time());
                keyframeCount++;
            }
            if (!channels.isEmpty()) bones.put(target, new NativeAnimation.TransformTracks(
                track(channels.get("position")), track(channels.get("rotation")), track(channels.get("scale"))));
        }
        if (latestTransformTime > length) {
            diagnostics.add(ModelDiagnostic.warning(modelId, "KEYFRAMES_AFTER_CLIP_END",
                "Animation '" + name + "' declares " + length + "s but has transform keyframes through "
                    + latestTransformTime + "s; tracks are preserved for interpolation and playback keeps the declared duration."));
        }
        timeline.sort(Comparator.comparingDouble(NativeAnimation.TimelineEvent::time));
        return new ParseAnimation(new NativeAnimation(name, length, loop, override, Map.copyOf(bones),
            List.copyOf(timeline)), keyframeCount);
    }

    private static int parseTimeline(JsonObject animator, List<NativeAnimation.TimelineEvent> destination,
                                     List<ModelDiagnostic> diagnostics, String modelId, String animationName,
                                     double length) {
        if (!animator.has("keyframes")) return 0;
        int count = 0;
        for (JsonElement element : array(animator, "keyframes")) {
            if (!element.isJsonObject()) throw new IllegalArgumentException("Timeline keyframe must be an object.");
            JsonObject frame = element.getAsJsonObject();
            String channel = string(frame, "channel");
            if ("sound".equals(channel)) {
                count += parseLegacySound(frame, destination, diagnostics, modelId, animationName, length);
                continue;
            }
            if (!"timeline".equals(channel)) {
                diagnostics.add(ModelDiagnostic.warning(modelId, "EFFECT_CHANNEL_IGNORED",
                    "Animation '" + animationName + "' ignores unsupported effect channel '" + channel + "'."));
                warnHostPaths(frame, diagnostics, modelId, animationName);
                continue;
            }
            double time = number(frame, "time");
            if (time > length) throw new IllegalArgumentException("Timeline keyframe exceeds animation length");
            JsonArray points = array(frame, "data_points");
            for (JsonElement point : points) {
                if (!point.isJsonObject() || !point.getAsJsonObject().has("script")) {
                    throw new IllegalArgumentException("Timeline keyframe has no script.");
                }
                String script = string(point.getAsJsonObject(), "script");
                addTimelineScript(destination, diagnostics, modelId, animationName, time, script);
                count++;
            }
        }
        return count;
    }

    /** Converts Blockbench's legacy sound animator into Armature's script form. */
    private static int parseLegacySound(JsonObject frame, List<NativeAnimation.TimelineEvent> destination,
                                        List<ModelDiagnostic> diagnostics, String modelId,
                                        String animationName, double length) {
        if (!frame.has("data_points") || !frame.get("data_points").isJsonArray()) return 0;
        warnHostPaths(frame, diagnostics, modelId, animationName);
        int count = 0;
        for (JsonElement pointElement : frame.getAsJsonArray("data_points")) {
            if (!pointElement.isJsonObject()) {
                throw new IllegalArgumentException("Sound keyframe data point must be an object.");
            }
            JsonObject point = pointElement.getAsJsonObject();
            String script = optionalText(point, "script");
            if (script == null) {
                String sound = optionalText(point, "effect", "sound", "name");
                if (sound == null) {
                    diagnostics.add(ModelDiagnostic.warning(modelId, "LEGACY_SOUND_UNRESOLVED",
                        "Animation '" + animationName
                            + "' contains a sound keyframe without a sound/effect identifier."));
                    continue;
                }
                StringBuilder builder = new StringBuilder("sound{sound=").append(sound);
                String locator = optionalText(point, "origin", "locator", "bone");
                if (locator != null) builder.append(";origin=").append(locator);
                // Blockbench stores these natively on sound keyframe data
                // points; carry them into the script so playback honors them.
                String volume = optionalText(point, "volume");
                if (volume != null) builder.append(";volume=").append(volume);
                String pitch = optionalText(point, "pitch");
                if (pitch != null) builder.append(";pitch=").append(pitch);
                String category = optionalText(point, "category", "soundcategory");
                if (category != null) builder.append(";category=").append(category);
                script = builder.append("}").toString();
            }
            double time = number(frame, "time");
            if (time > length) throw new IllegalArgumentException("Timeline keyframe exceeds animation length");
            addTimelineScript(destination, diagnostics, modelId, animationName, time, script);
            count++;
        }
        return count;
    }

    private static void addTimelineScript(List<NativeAnimation.TimelineEvent> destination,
                                           List<ModelDiagnostic> diagnostics, String modelId,
                                           String animationName, double time, String script) {
        destination.add(new NativeAnimation.TimelineEvent(time, script));
        if (new TimelineCursor().parse(script) instanceof TimelineCommand.UnknownScript) {
            diagnostics.add(ModelDiagnostic.warning(modelId, "TIMELINE_SCRIPT_UNKNOWN",
                "Animation '" + animationName + "' contains unsupported timeline script '" + script + "'."));
        }
    }

    private static void warnHostPaths(JsonObject frame, List<ModelDiagnostic> diagnostics,
                                      String modelId, String animationName) {
        if (!frame.has("data_points") || !frame.get("data_points").isJsonArray()) return;
        for (JsonElement point : frame.getAsJsonArray("data_points")) {
            if (!point.isJsonObject()) continue;
            for (String field : List.of("file", "path", "sound")) {
                if (!point.getAsJsonObject().has(field) || !point.getAsJsonObject().get(field).isJsonPrimitive()) continue;
                String value = point.getAsJsonObject().get(field).getAsString();
                if (value.matches("^[A-Za-z]:[\\\\/].*") || value.startsWith("/")) {
                    diagnostics.add(ModelDiagnostic.warning(modelId, "HOST_PATH_IGNORED",
                        "Animation '" + animationName + "' contains host-local path in '" + field
                            + "'; the path is never imported into generated assets."));
                }
            }
        }
    }

    private static String optionalText(JsonObject object, String... fields) {
        for (String field : fields) {
            if (!object.has(field) || !object.get(field).isJsonPrimitive()) continue;
            String value = object.get(field).getAsString().trim();
            if (!value.isEmpty()) return value;
        }
        return null;
    }

    private static AnimationKeyframe parseFrame(JsonObject frame, double length, String channel,
                                                CoordinateSystem coordinateSystem,
                                                List<ModelDiagnostic> diagnostics, String modelId,
                                                String animationName, String boneId,
                                                MolangContext molangContext) {
        if (!frame.has("data_points") || !frame.get("data_points").isJsonArray()) {
            // Blockbench writes a keyframe with no data points when an axis was left
            // blank in the editor. Treat it as the channel's rest value instead of
            // rejecting the whole model.
            diagnostics.add(ModelDiagnostic.warning(modelId, "EMPTY_KEYFRAME_VALUE",
                "Animation '" + animationName + "' bone '" + boneId + "' channel '" + channel
                    + "' keyframe at " + frame.get("time") + "s has no data points; using the rest value."));
            return emptyKeyframe(frame, channel, coordinateSystem, molangContext);
        }
        JsonArray points = frame.getAsJsonArray("data_points");
        Vector3 value = null;
        MolangVector molang = null;
        boolean conflictingPoints = false;
        for (JsonElement pointElement : points) {
            if (!pointElement.isJsonObject()) continue;
            JsonObject point = pointElement.getAsJsonObject();
            // A data point with no axis carrying an actual value is as empty as a
            // keyframe with no data points at all.
            if (isBlankPoint(point)) continue;
            MolangVector candidate = vectorWithMolang(point, channel, coordinateSystem,
                diagnostics, modelId, animationName, boneId, molangContext);
            if (value == null) {
                value = candidate.fallback();
                molang = candidate;
            } else if (!value.equals(candidate.fallback())) {
                conflictingPoints = true;
            }
        }
        if (molang == null) {
            diagnostics.add(ModelDiagnostic.warning(modelId, "EMPTY_KEYFRAME_VALUE",
                "Animation '" + animationName + "' bone '" + boneId + "' channel '" + channel
                    + "' keyframe at " + frame.get("time") + "s has no usable data point; using the rest value."));
            return emptyKeyframe(frame, channel, coordinateSystem, molangContext);
        }
        if (conflictingPoints) {
            diagnostics.add(ModelDiagnostic.warning(modelId, "NUMERIC_DATA_POINTS_COLLAPSED",
                "Animation '" + animationName + "' bone '" + boneId + "' channel '" + channel
                    + "' has multiple numeric data points; the first point is retained to match BetterModel."));
        }
        double time = number(frame, "time");
        // Trailing transform keys can be interpolation control points. The playback clock,
        // not the track's final key, owns the clip duration (also used by additive clips).
        return new AnimationKeyframe(time, value, interpolation(frame),
            handle(frame, "bezier_right_time", "bezier_right_value", time, channel, coordinateSystem, molangContext),
            handle(frame, "bezier_left_time", "bezier_left_value", time, channel, coordinateSystem, molangContext),
            molang);
    }

    /**
     * Whether a data point carries no usable axis value, which is how Blockbench
     * exports a keyframe the author left blank.
     */
    private static boolean isBlankPoint(JsonObject point) {
        for (String field : new String[] {"x", "y", "z"}) {
            if (!point.has(field) || point.get(field).isJsonNull()) continue;
            if (!point.get(field).isJsonPrimitive()) return false;
            if (!point.get(field).getAsString().trim().isEmpty()) return false;
        }
        return true;
    }

    /** Builds the rest-pose keyframe Blockbench emits when a data point was left blank. */
    private static AnimationKeyframe emptyKeyframe(JsonObject frame, String channel,
                                                   CoordinateSystem coordinateSystem,
                                                   MolangContext molangContext) {
        double time = frame.has("time") && frame.get("time").isJsonPrimitive()
            && Double.isFinite(frame.get("time").getAsDouble())
                ? Math.max(0.0, frame.get("time").getAsDouble()) : 0.0;
        Vector3 rest = coordinateSystem.transform(channel, CoordinateSystem.neutral(channel));
        return new AnimationKeyframe(time, rest, interpolation(frame),
            handle(frame, "bezier_right_time", "bezier_right_value", time, channel, coordinateSystem, molangContext),
            handle(frame, "bezier_left_time", "bezier_left_value", time, channel, coordinateSystem, molangContext));
    }

    /**
     * Builds one transform keyframe value, keeping Molang source for any axis that was
     * authored as an expression. The coordinate transform runs after the Molang
     * resolves, so a dynamic axis stays dynamic in animation space.
     */
    private static MolangVector vectorWithMolang(JsonObject point, String channel,
                                                 CoordinateSystem coordinateSystem,
                                                 List<ModelDiagnostic> diagnostics, String modelId,
                                                 String animationName, String boneId,
                                                 MolangContext context) {
        BlockbenchNumericParser.Axis x = axis(point, "x", channel, coordinateSystem, diagnostics, modelId,
            animationName, boneId, context);
        BlockbenchNumericParser.Axis y = axis(point, "y", channel, coordinateSystem, diagnostics, modelId,
            animationName, boneId, context);
        BlockbenchNumericParser.Axis z = axis(point, "z", channel, coordinateSystem, diagnostics, modelId,
            animationName, boneId, context);
        if (!x.isExpression() && !y.isExpression() && !z.isExpression()) {
            return MolangVector.constant(
                coordinateSystem.transform(channel, new Vector3(x.constant(), y.constant(), z.constant())),
                value -> value);
        }
        // The fallback is the compile-time resolution; per-sample evaluation re-applies
        // the channel transform so a dynamic axis stays dynamic in animation space.
        Vector3 fallback = coordinateSystem.transform(channel,
            new Vector3(x.resolve(context), y.resolve(context), z.resolve(context)));
        return new MolangVector(fallback, x.molang(), y.molang(), z.molang(),
            mapper(coordinateSystem, channel));
    }

    /** Parses one axis, degrading an unparseable expression to zero with a warning. */
    private static BlockbenchNumericParser.Axis axis(JsonObject point, String field, String channel,
                                                     CoordinateSystem coordinateSystem,
                                                     List<ModelDiagnostic> diagnostics, String modelId,
                                                     String animationName, String boneId,
                                                     MolangContext context) {
        try {
            return BlockbenchNumericParser.parse(point.has(field) ? point.get(field) : null, field, context);
        } catch (RuntimeException exception) {
            diagnostics.add(ModelDiagnostic.warning(modelId, "MOLANG_VALUE_UNRESOLVED",
                "Animation '" + animationName + "' bone '" + boneId + "' channel '" + channel + "' axis '"
                    + field + "' is not a supported numeric value (" + exception.getMessage()
                    + "); using zero."));
            return BlockbenchNumericParser.Axis.constant(0.0);
        }
    }

    private static AnimationKeyframe.Handle handle(JsonObject frame, String timeField,
                                                    String valueField, double frameTime, String channel,
                                                    CoordinateSystem coordinateSystem,
                                                    MolangContext molangContext) {
        if (!frame.has(timeField) && !frame.has(valueField)) return null;
        if (!frame.has(timeField) || !frame.has(valueField)) {
            throw new IllegalArgumentException("Incomplete Bézier handle.");
        }
        Vector3 relativeTime = vectorWithMolang(frame.get(timeField), timeField, molangContext);
        Vector3 value = coordinateSystem.transform(channel, vectorWithMolang(frame.get(valueField), valueField, molangContext));
        return new AnimationKeyframe.Handle(new Vector3(frameTime + relativeTime.x(),
            frameTime + relativeTime.y(), frameTime + relativeTime.z()), value);
    }

    private static Vector3 vectorWithMolang(JsonElement source, String field, MolangContext context) {
        if (!source.isJsonArray() || source.getAsJsonArray().size() != 3) {
            throw new IllegalArgumentException("Invalid Bézier vector '" + field + "'.");
        }
        JsonArray values = source.getAsJsonArray();
        try {
            double x = BlockbenchNumericParser.parseWithMolangContext(values.get(0), field + "[0]", context);
            double y = BlockbenchNumericParser.parseWithMolangContext(values.get(1), field + "[1]", context);
            double z = BlockbenchNumericParser.parseWithMolangContext(values.get(2), field + "[2]", context);
            return new Vector3(x, y, z);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Non-numeric Bézier vector '" + field + "'.", exception);
        }
    }

    /** Adapts the channel's coordinate transform to the {@link MolangVector} mapper. */
    private static java.util.function.UnaryOperator<Vector3> mapper(CoordinateSystem coordinateSystem,
                                                                    String channel) {
        return value -> coordinateSystem.transform(channel, value);
    }

    private static Vector3 vector(JsonElement source, String field) {
        if (!source.isJsonArray() || source.getAsJsonArray().size() != 3) {
            throw new IllegalArgumentException("Invalid Bézier vector '" + field + "'.");
        }
        JsonArray values = source.getAsJsonArray();
        try {
            double x = BlockbenchNumericParser.parse(values.get(0), field + "[0]");
            double y = BlockbenchNumericParser.parse(values.get(1), field + "[1]");
            double z = BlockbenchNumericParser.parse(values.get(2), field + "[2]");
            return new Vector3(x, y, z);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Non-numeric Bézier vector '" + field + "'.", exception);
        }
    }

    private static AnimationTrack track(List<AnimationKeyframe> frames) {
        return frames == null ? null : new AnimationTrack(frames);
    }

    private static AnimationKeyframe.Interpolation interpolation(JsonObject frame) {
        String value = frame.has("interpolation") ? string(frame, "interpolation") : "linear";
        return switch (value) {
            case "linear" -> AnimationKeyframe.Interpolation.LINEAR;
            case "step" -> AnimationKeyframe.Interpolation.STEP;
            case "catmullrom" -> AnimationKeyframe.Interpolation.CATMULLROM;
            case "bezier" -> AnimationKeyframe.Interpolation.BEZIER;
            default -> throw new IllegalArgumentException("Unsupported Blockbench interpolation '" + value + "'.");
        };
    }

    private static JsonArray array(JsonObject object, String field) {
        if (!object.has(field) || !object.get(field).isJsonArray()) throw new IllegalArgumentException("Missing array '" + field + "'.");
        return object.getAsJsonArray(field);
    }
    private static JsonObject object(JsonObject object, String field) {
        if (!object.has(field) || !object.get(field).isJsonObject()) throw new IllegalArgumentException("Missing object '" + field + "'.");
        return object.getAsJsonObject(field);
    }
    /** BetterModel resolves an animator by its {@code name} first, then by the map key. */
    private static String animatorName(JsonObject animator, String key) {
        JsonElement name = animator.get("name");
        if (name == null || !name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString()) {
            return key;
        }
        String text = name.getAsString();
        return text.isBlank() ? key : text;
    }

    private static String string(JsonObject object, String field) {
        if (!object.has(field) || !object.get(field).isJsonPrimitive()) throw new IllegalArgumentException("Missing string '" + field + "'.");
        return object.get(field).getAsString();
    }
    private static double number(JsonObject object, String field) {
        try { double value = object.get(field).getAsDouble(); if (!Double.isFinite(value)) throw new NumberFormatException(); return value; }
        catch (RuntimeException exception) { throw new IllegalArgumentException("Missing finite number '" + field + "'.", exception); }
    }

    private static void validateOrdered(Map<String, List<AnimationKeyframe>> channels,
                                        String animationName, String boneId) {
        for (Map.Entry<String, List<AnimationKeyframe>> entry : channels.entrySet()) {
            double previous = -1.0;
            for (AnimationKeyframe frame : entry.getValue()) {
                if (frame.time() <= previous) {
                    throw new IllegalArgumentException("Animation '" + animationName + "' bone '" + boneId
                        + "' channel '" + entry.getKey() + "' keyframes must be strictly ordered");
                }
                previous = frame.time();
            }
        }
    }

    /** The same animation-space conversions used by BetterModel's ModelMeta. */
    private enum CoordinateSystem {
        BLOCKBENCH_5 {
            @Override Vector3 transform(String channel, Vector3 value) {
                return switch (channel) {
                    case "position", "rotation" -> new Vector3(-value.x(), value.y(), -value.z());
                    case "scale" -> value.subtract(new Vector3(1, 1, 1));
                    default -> throw new IllegalArgumentException("Unsupported animation channel: " + channel);
                };
            }
        },
        LEGACY {
            @Override Vector3 transform(String channel, Vector3 value) {
                return switch (channel) {
                    case "position" -> new Vector3(value.x(), value.y(), -value.z());
                    case "rotation" -> new Vector3(value.x(), -value.y(), -value.z());
                    case "scale" -> value.subtract(new Vector3(1, 1, 1));
                    default -> throw new IllegalArgumentException("Unsupported animation channel: " + channel);
                };
            }
        };

        abstract Vector3 transform(String channel, Vector3 value);

        /**
         * The rest value for a channel, used when Blockbench wrote a keyframe with
         * no data point. Position and rotation rest at zero; scale rests at one,
         * which in this offset-from-one encoding is a zero offset rather than the
         * degenerate -1 that a raw zero would produce.
         */
        static Vector3 neutral(String channel) {
            return switch (channel) {
                case "position", "rotation" -> new Vector3(0, 0, 0);
                case "scale" -> new Vector3(1, 1, 1);
                default -> throw new IllegalArgumentException("Unsupported animation channel: " + channel);
            };
        }

        static CoordinateSystem from(JsonObject model) {
            JsonObject meta = model.has("meta") && model.get("meta").isJsonObject()
                ? model.getAsJsonObject("meta") : null;
            if (meta == null || !meta.has("format_version")) return LEGACY;
            try {
                return Double.parseDouble(meta.get("format_version").getAsString()) >= 5.0
                    ? BLOCKBENCH_5 : LEGACY;
            } catch (RuntimeException ignored) {
                return LEGACY;
            }
        }
    }

    public record ParseResult(List<NativeAnimation> animations, List<ModelDiagnostic> diagnostics) {
        public ParseResult {
            animations = List.copyOf(animations);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private record ParseAnimation(NativeAnimation animation, int keyframeCount) {
    }
}
