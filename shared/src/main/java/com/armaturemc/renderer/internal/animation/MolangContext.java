package com.armaturemc.renderer.internal.animation;

import java.util.Map;
import java.util.function.DoubleSupplier;

/**
 * Runtime values a Molang expression can read.
 *
 * <p>One context is built per sampled frame from the viewer's live state, so
 * {@code query.yaw} and {@code math.random} resolve at playback time rather than
 * being frozen when the model is compiled. {@link #defaults()} is the neutral
 * context used when no viewer state is available.
 */
public record MolangContext(
    double yaw,
    double pitch,
    double modifiedDistanceMoved,
    double modifiedMoveSpeed,
    double animTime,
    double deltaTime,
    double gameTime,
    double programTime,
    double lifetime,
    double health,
    double hurtTime,
    boolean isMoving,
    boolean isOnGround,
    boolean isSneaking,
    boolean isSprinting,
    boolean isSwimming,
    Map<String, DoubleSupplier> variables,
    DoubleSupplier random) {

    public MolangContext {
        if (variables == null) variables = Map.of();
        random = random == null ? DEFAULT_RANDOM : random;
    }

    private static final DoubleSupplier DEFAULT_RANDOM =
        () -> java.util.concurrent.ThreadLocalRandom.current().nextDouble();

    /** Neutral context: a standing viewer with no motion and zero clocks. */
    public static MolangContext defaults() {
        return new MolangContext(0, 0, 0, 0, 0, 1.0 / 60.0, 0, 0, 0, 20, 0, false, true, false, false, false,
            Map.of(), DEFAULT_RANDOM);
    }

    /**
     * Context for a sampled animation frame.
     *
     * @param yaw viewer yaw in degrees
     * @param pitch viewer pitch in degrees
     * @param animTime seconds since the clip started
     * @param deltaTime seconds since the previous frame
     * @param gameTime seconds of world time
     */
    public static MolangContext forAnimation(double yaw, double pitch, double animTime,
                                            double deltaTime, double gameTime) {
        return defaults().withViewer(yaw, pitch).withClocks(animTime, deltaTime, gameTime);
    }

    /** Returns a copy that observes the given viewer orientation. */
    public MolangContext withViewer(double yaw, double pitch) {
        return new MolangContext(yaw, pitch, modifiedDistanceMoved, modifiedMoveSpeed, animTime, deltaTime,
            gameTime, programTime, lifetime, health, hurtTime, isMoving, isOnGround, isSneaking, isSprinting,
            isSwimming, variables, random);
    }

    /** Returns a copy carrying this frame's animation clocks. */
    public MolangContext withClocks(double animTime, double deltaTime, double gameTime) {
        return new MolangContext(yaw, pitch, modifiedDistanceMoved, modifiedMoveSpeed, animTime, deltaTime,
            gameTime, programTime, lifetime, health, hurtTime, isMoving, isOnGround, isSneaking, isSprinting,
            isSwimming, variables, random);
    }

    /** Returns a copy with the given motion, used for {@code query.is_moving} and friends. */
    public MolangContext withMotion(double distanceMoved, double moveSpeed, boolean moving) {
        return new MolangContext(yaw, pitch, distanceMoved, moveSpeed, animTime, deltaTime, gameTime, programTime,
            lifetime, health, hurtTime, moving, isOnGround, isSneaking, isSprinting, isSwimming, variables, random);
    }

    /** Returns a copy whose {@code math.random} draws from {@code source}, for reproducible sampling. */
    public MolangContext withRandom(DoubleSupplier source) {
        return new MolangContext(yaw, pitch, modifiedDistanceMoved, modifiedMoveSpeed, animTime, deltaTime,
            gameTime, programTime, lifetime, health, hurtTime, isMoving, isOnGround, isSneaking, isSprinting,
            isSwimming, variables, source);
    }

    /** Evaluates a one-shot expression against this context. */
    public double evaluate(String expression) {
        return MolangExpression.evaluate(expression, this);
    }
}