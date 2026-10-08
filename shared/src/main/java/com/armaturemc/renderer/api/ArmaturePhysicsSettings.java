package com.armaturemc.renderer.api;

/** Backend-neutral physics-driven bone configuration. */
public record ArmaturePhysicsSettings(boolean enabled, double accelerationGain,
                                      double maximumAngle, double frequency, double damping,
                                      double turnGain) {
    private static final double MAX_GAIN = 1_000.0;
    private static final double MAX_ROTATION = 180.0;
    private static final double MAX_FREQUENCY = 100.0;
    private static final double MAX_DAMPING = 10.0;

    public ArmaturePhysicsSettings {
        finiteRange("accelerationGain", accelerationGain, -MAX_GAIN, MAX_GAIN);
        finiteRange("maximumAngle", maximumAngle, 0.0, MAX_ROTATION);
        finiteRange("frequency", frequency, 0.0, MAX_FREQUENCY);
        finiteRange("damping", damping, 0.0, MAX_DAMPING);
        finiteRange("turnGain", turnGain, 0.0, MAX_GAIN);
    }

    public static ArmaturePhysicsSettings defaults() {
        return new ArmaturePhysicsSettings(true, 4.0, 25.0, 4.0, 0.6, 0.25);
    }

    /** Physics settings without any swing effect. */
    public static ArmaturePhysicsSettings off() {
        return new ArmaturePhysicsSettings(false, 0.0, 25.0, 4.0, 0.6, 0.25);
    }

    private static void finiteRange(String name, double value, double minimum, double maximum) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be finite and within ["
                + minimum + ", " + maximum + "], got " + value);
        }
    }
}
