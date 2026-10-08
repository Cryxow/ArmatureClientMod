package com.armaturemc.renderer.internal.animation;

import java.util.function.UnaryOperator;

/**
 * The authored value of one transform keyframe, keeping Molang source when the
 * data point is an expression.
 *
 * <p>Blockbench data points are text: {@code 12.5}, {@code -2.5-90}, or a Molang
 * expression such as {@code math.sin(query.anim_time * 30)}. Constants resolve
 * when the model is compiled; expressions are retained here and evaluated on
 * every sample, so {@code math.random} and query-driven poses stay dynamic.
 *
 * <p>{@link #fallback()} is the value used by any caller that has no context,
 * such as a static pose probe or a regression test on a constant keyframe.
 */
public record MolangVector(Vector3 fallback, MolangExpression x, MolangExpression y, MolangExpression z,
                           UnaryOperator<Vector3> coordinateTransform) {

    public MolangVector {
        if (fallback == null) throw new IllegalArgumentException("Fallback value is required");
        if (coordinateTransform == null) throw new IllegalArgumentException("Coordinate transform is required");
    }

    /** A purely constant value, with no per-sample work. */
    public static MolangVector constant(Vector3 value, UnaryOperator<Vector3> coordinateTransform) {
        return new MolangVector(value, null, null, null, coordinateTransform);
    }

    /** Whether any axis carries an expression that must be evaluated per sample. */
    public boolean isDynamic() {
        return x != null || y != null || z != null;
    }

    /** Evaluates against {@code context}, or returns the compile-time value when it is null. */
    public Vector3 evaluate(MolangContext context) {
        if (!isDynamic() || context == null) return fallback;
        Vector3 raw = new Vector3(evaluate(x, context, fallback.x()),
            evaluate(y, context, fallback.y()),
            evaluate(z, context, fallback.z()));
        Vector3 transformed = coordinateTransform.apply(raw);
        return transformed.isFinite() ? transformed : fallback;
    }

    private static double evaluate(MolangExpression expression, MolangContext context, double fallback) {
        if (expression == null) return fallback;
        try {
            double value = expression.evaluate(context);
            return Double.isFinite(value) ? value : fallback;
        } catch (RuntimeException exception) {
            // A single broken axis must not drop the whole model; hold the
            // compile-time value for this sample instead.
            return fallback;
        }
    }

    /** Resolves with the neutral context, for callers outside the playback path. */
    public Vector3 evaluate() {
        return evaluate(null);
    }
}