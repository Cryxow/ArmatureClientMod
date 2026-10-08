package com.armaturemc.renderer.internal.animation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Deterministic sampler for one numeric Blockbench channel. */
public final class AnimationTrack {
    private final List<AnimationKeyframe> frames;

    public AnimationTrack(List<AnimationKeyframe> frames) {
        if (frames == null || frames.isEmpty()) throw new IllegalArgumentException("Animation track needs a keyframe");
        List<AnimationKeyframe> ordered = new ArrayList<>(frames);
        ordered.sort(Comparator.comparingDouble(AnimationKeyframe::time));
        for (int index = 1; index < ordered.size(); index++) {
            if (ordered.get(index - 1).time() == ordered.get(index).time()) {
                throw new IllegalArgumentException("Animation track contains duplicate keyframe times");
            }
        }
        this.frames = List.copyOf(ordered);
    }

    public Vector3 sample(double time) {
        return sample(this.frames, time, null);
    }

    /** Samples authored Euler degrees without rewriting full turns into a shorter path. */
    public Vector3 sampleAngles(double time) {
        return sample(this.frames, time, null);
    }

    /**
     * Samples with the viewer's live Molang state, so keyframes authored as
     * expressions resolve per frame instead of using the compile-time value.
     */
    public Vector3 sample(double time, MolangContext context) {
        return sample(this.frames, time, context);
    }

    /** Angle variant that evaluates Molang keyframes for this frame. */
    public Vector3 sampleAngles(double time, MolangContext context) {
        return sample(this.frames, time, context);
    }

    /** Whether any keyframe on this track carries a Molang expression. */
    public boolean isDynamic() {
        return frames.stream().anyMatch(frame -> frame.molang().isDynamic());
    }

    /** Authored checkpoints used by deterministic pose regression tests. */
    public List<Double> keyframeTimes() {
        return frames.stream().map(AnimationKeyframe::time).toList();
    }

    private static Vector3 sample(List<AnimationKeyframe> frames, double time, MolangContext context) {
        if (!Double.isFinite(time)) throw new IllegalArgumentException("Sample time must be finite");
        if (time <= frames.getFirst().time()) return frames.getFirst().value(context);
        if (time >= frames.getLast().time()) return frames.getLast().value(context);
        int right = 1;
        while (frames.get(right).time() < time) right++;
        AnimationKeyframe leftFrame = frames.get(right - 1);
        AnimationKeyframe rightFrame = frames.get(right);
        double span = rightFrame.time() - leftFrame.time();
        double progress = (time - leftFrame.time()) / span;
        return switch (leftFrame.interpolation()) {
            case STEP -> leftFrame.value(context);
            case LINEAR -> Vector3.lerp(leftFrame.value(context), rightFrame.value(context), progress);
            case CATMULLROM -> catmullRom(resolve(right - 2 < 0 ? leftFrame : frames.get(right - 2), context),
                resolve(leftFrame, context), resolve(rightFrame, context),
                resolve(right + 1 >= frames.size() ? rightFrame : frames.get(right + 1), context), progress);
            case BEZIER -> bezier(leftFrame, rightFrame, progress, context);
        };
    }

    /** Resolves a keyframe for this sample, falling back to its constant value. */
    private static Vector3 resolve(AnimationKeyframe frame, MolangContext context) {
        return context == null ? frame.value() : frame.value(context);
    }

    private static Vector3 catmullRom(Vector3 previous, Vector3 start, Vector3 end, Vector3 next, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        return previous.multiply(-0.5 * t3 + t2 - 0.5 * t)
            .add(start.multiply(1.5 * t3 - 2.5 * t2 + 1.0))
            .add(end.multiply(-1.5 * t3 + 2.0 * t2 + 0.5 * t))
            .add(next.multiply(0.5 * t3 - 0.5 * t2));
    }

    private static Vector3 bezier(AnimationKeyframe start, AnimationKeyframe end, double progress,
                                  MolangContext context) {
        AnimationKeyframe.Handle right = start.rightHandle();
        AnimationKeyframe.Handle left = end.leftHandle();
        Vector3 startValue = resolve(start, context);
        Vector3 endValue = resolve(end, context);
        if (right == null || left == null) return Vector3.lerp(startValue, endValue, progress);
        double targetTime = start.time() + (end.time() - start.time()) * progress;
        return new Vector3(bezierAxis(startValue.x(), right.value().x(), left.value().x(), endValue.x(),
                start.time(), right.time().x(), left.time().x(), end.time(), targetTime),
            bezierAxis(startValue.y(), right.value().y(), left.value().y(), endValue.y(),
                start.time(), right.time().y(), left.time().y(), end.time(), targetTime),
            bezierAxis(startValue.z(), right.value().z(), left.value().z(), endValue.z(),
                start.time(), right.time().z(), left.time().z(), end.time(), targetTime));
    }

    private static double bezierAxis(double a, double b, double c, double d,
                                     double startTime, double rightTime, double leftTime,
                                     double endTime, double targetTime) {
        return cubic(a, b, c, d, solveBezierTime(startTime, rightTime, leftTime, endTime, targetTime));
    }

    private static double solveBezierTime(double a, double b, double c, double d, double target) {
        double low = 0.0;
        double high = 1.0;
        for (int iteration = 0; iteration < 32; iteration++) {
            double middle = (low + high) * 0.5;
            if (cubic(a, b, c, d, middle) < target) low = middle; else high = middle;
        }
        return (low + high) * 0.5;
    }

    private static double cubic(double a, double b, double c, double d, double t) {
        double inverse = 1.0 - t;
        return inverse * inverse * inverse * a + 3.0 * inverse * inverse * t * b
            + 3.0 * inverse * t * t * c + t * t * t * d;
    }

    private static Vector3 cubic(Vector3 a, Vector3 b, Vector3 c, Vector3 d, double t) {
        return a.multiply(Math.pow(1.0 - t, 3)).add(b.multiply(3.0 * Math.pow(1.0 - t, 2) * t))
            .add(c.multiply(3.0 * (1.0 - t) * t * t)).add(d.multiply(t * t * t));
    }
}
