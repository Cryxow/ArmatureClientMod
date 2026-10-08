package com.armaturemc.renderer.api;

import java.util.Objects;

/** Backend-neutral procedural motion configuration. */
public record ArmatureMotionSettings(ArmatureSwaySettings sway, Bob bob, CameraFollow cameraFollow,
                                     ArmaturePhysicsSettings physics) {
    public ArmatureMotionSettings {
        Objects.requireNonNull(sway, "sway cannot be null");
        Objects.requireNonNull(bob, "bob cannot be null");
        Objects.requireNonNull(cameraFollow, "cameraFollow cannot be null");
        physics = physics == null ? ArmaturePhysicsSettings.defaults() : physics;
    }

    public static ArmatureMotionSettings defaults() {
        return new ArmatureMotionSettings(ArmatureSwaySettings.defaults(),
            new Bob(true, 1.8, 0.025, 0.018, 0.8, 9.0, 0.9),
            new CameraFollow(true, 9.0, 0.9, 0.0025, 0.0020, 0.035, 0.20),
            ArmaturePhysicsSettings.defaults());
    }

    public record Bob(boolean enabled, double cyclesPerBlock, double horizontalAmplitude,
                      double verticalAmplitude, double rollAmplitude, double frequency, double damping) {
        private static final double MAX_CYCLES_PER_BLOCK = 1_000.0;
        private static final double MAX_AMPLITUDE = 1_000.0;
        private static final double MAX_FREQUENCY = 100.0;
        private static final double MAX_DAMPING = 10.0;

        public Bob {
            finiteRange("cyclesPerBlock", cyclesPerBlock, 0.0, MAX_CYCLES_PER_BLOCK);
            finiteRange("horizontalAmplitude", horizontalAmplitude, 0.0, MAX_AMPLITUDE);
            finiteRange("verticalAmplitude", verticalAmplitude, 0.0, MAX_AMPLITUDE);
            finiteRange("rollAmplitude", rollAmplitude, 0.0, MAX_AMPLITUDE);
            finiteRange("frequency", frequency, 0.0, MAX_FREQUENCY);
            finiteRange("damping", damping, 0.0, MAX_DAMPING);
        }
    }

    public record CameraFollow(boolean enabled, double frequency, double damping,
                               double yawPositionGain, double pitchPositionGain,
                               double maximumOffset, double pitchCompensation) {
        private static final double MAX_FREQUENCY = 100.0;
        private static final double MAX_DAMPING = 10.0;
        private static final double MAX_GAIN = 1_000.0;
        private static final double MAX_OFFSET = 100.0;

        public CameraFollow {
            finiteRange("frequency", frequency, 0.0, MAX_FREQUENCY);
            finiteRange("damping", damping, 0.0, MAX_DAMPING);
            finiteRange("yawPositionGain", yawPositionGain, -MAX_GAIN, MAX_GAIN);
            finiteRange("pitchPositionGain", pitchPositionGain, -MAX_GAIN, MAX_GAIN);
            finiteRange("maximumOffset", maximumOffset, 0.0, MAX_OFFSET);
            finiteRange("pitchCompensation", pitchCompensation, 0.0, MAX_OFFSET);
        }
    }

    private static void finiteRange(String name, double value, double minimum, double maximum) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be finite and within ["
                + minimum + ", " + maximum + "], got " + value);
        }
    }
}
