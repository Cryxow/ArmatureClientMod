package com.armaturemc.renderer.api;

/** Immutable camera and movement snapshot safe to pass out of a packet callback. */
public record RenderMotionInput(boolean hasCameraDelta, double yawDelta, double pitchDelta,
                                double strafeAxis, double forwardAxis) {
    public RenderMotionInput {
        if (!Double.isFinite(yawDelta) || !Double.isFinite(pitchDelta)
            || !Double.isFinite(strafeAxis) || !Double.isFinite(forwardAxis)) {
            throw new IllegalArgumentException("Motion input values must be finite");
        }
        strafeAxis = clampAxis(strafeAxis);
        forwardAxis = clampAxis(forwardAxis);
    }

    public static RenderMotionInput fallback(double strafeAxis, double forwardAxis) {
        return new RenderMotionInput(false, 0.0, 0.0, strafeAxis, forwardAxis);
    }

    public static RenderMotionInput captured(double yawDelta, double pitchDelta,
                                             double strafeAxis, double forwardAxis) {
        return new RenderMotionInput(true, yawDelta, pitchDelta, strafeAxis, forwardAxis);
    }

    private static double clampAxis(double value) {
        return Math.max(-1.0, Math.min(1.0, value));
    }
}
