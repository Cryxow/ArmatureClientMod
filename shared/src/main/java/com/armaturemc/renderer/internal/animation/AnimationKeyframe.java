package com.armaturemc.renderer.internal.animation;

import java.util.function.UnaryOperator;

/** One numeric Blockbench transform keyframe and optional cubic Bézier handles. */
public record AnimationKeyframe(double time, Vector3 value, Interpolation interpolation,
                                Handle rightHandle, Handle leftHandle, MolangVector molang) {
    public enum Interpolation { LINEAR, STEP, CATMULLROM, BEZIER }
    public record Handle(Vector3 time, Vector3 value) {
    }

    public AnimationKeyframe {
        if (!Double.isFinite(time) || time < 0.0) throw new IllegalArgumentException("Keyframe time must be finite and non-negative");
        if (value == null || interpolation == null) throw new IllegalArgumentException("Keyframe value and interpolation are required");
        if (molang == null) throw new IllegalArgumentException("Keyframe value carrier is required");
    }

    /** Constant keyframe, used by the animation clip format and by tests. */
    public AnimationKeyframe(double time, Vector3 value, Interpolation interpolation,
                             Handle rightHandle, Handle leftHandle) {
        this(time, value, interpolation, rightHandle, leftHandle, MolangVector.constant(value, UnaryOperator.identity()));
    }

    /** Resolves this keyframe for a sampled frame, evaluating any Molang axes. */
    public Vector3 value(MolangContext context) {
        return molang.evaluate(context);
    }
}