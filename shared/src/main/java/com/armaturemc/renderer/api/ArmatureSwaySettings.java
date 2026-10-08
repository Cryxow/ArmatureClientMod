package com.armaturemc.renderer.api;


/** Backend-neutral procedural sway configuration. */
public record ArmatureSwaySettings(boolean enabled, double lookLerpSpeed, double movementLerpSpeed,
                                   double lookYawGain, double lookPitchGain, double lookRollGain,
                                   double movementYawGain, double movementPitchGain,
                                   double movementRollGain, double maximumRotation,
                                   boolean affectsCameraBone) {
    private static final double MAX_SPEED = 1_000.0;
    private static final double MAX_GAIN = 1_000.0;
    private static final double MAX_ROTATION = 180.0;

    public ArmatureSwaySettings {
        finiteRange("lookLerpSpeed", lookLerpSpeed, 0.0, MAX_SPEED);
        finiteRange("movementLerpSpeed", movementLerpSpeed, 0.0, MAX_SPEED);
        finiteRange("lookYawGain", lookYawGain, -MAX_GAIN, MAX_GAIN);
        finiteRange("lookPitchGain", lookPitchGain, -MAX_GAIN, MAX_GAIN);
        finiteRange("lookRollGain", lookRollGain, -MAX_GAIN, MAX_GAIN);
        finiteRange("movementYawGain", movementYawGain, -MAX_GAIN, MAX_GAIN);
        finiteRange("movementPitchGain", movementPitchGain, -MAX_GAIN, MAX_GAIN);
        finiteRange("movementRollGain", movementRollGain, -MAX_GAIN, MAX_GAIN);
        finiteRange("maximumRotation", maximumRotation, 0.0, MAX_ROTATION);
    }

    public ArmatureSwaySettings(boolean enabled, double lookLerpSpeed, double movementLerpSpeed,
                                double lookYawGain, double lookPitchGain, double lookRollGain,
                                double movementYawGain, double movementPitchGain,
                                double movementRollGain, double maximumRotation) {
        this(enabled, lookLerpSpeed, movementLerpSpeed, lookYawGain, lookPitchGain,
            lookRollGain, movementYawGain, movementPitchGain, movementRollGain,
            maximumRotation, true);
    }

    public static ArmatureSwaySettings defaults() {
        return new ArmatureSwaySettings(true, 0.0, 14.0,
            0.12, 0.10, 0.05, 0.20, 0.06, 0.35, 4.0, true);
    }

    private static void finiteRange(String name, double value, double minimum, double maximum) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be finite and within ["
                + minimum + ", " + maximum + "], got " + value);
        }
    }
}
