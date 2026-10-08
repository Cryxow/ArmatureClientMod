package com.armaturemc.core.motion;

public final class DampedSpring {
    private static final double MAX_OMEGA_DELTA = 0.1;
    private double value;
    private double velocity;
    private final double frequency;
    private final double dampingRatio;

    public DampedSpring(double frequency, double dampingRatio) {
        this.frequency = frequency;
        this.dampingRatio = dampingRatio;
    }

    public double update(double target, double deltaSeconds) {
        if (deltaSeconds <= 0.0 || frequency <= 0.0) {
            return value;
        }

        double omega = 2.0 * Math.PI * frequency;
        int steps = Math.max(1, Math.min(64, (int) Math.ceil(omega * deltaSeconds / MAX_OMEGA_DELTA)));
        double step = deltaSeconds / steps;
        for (int i = 0; i < steps; i++) {
            double acceleration = omega * omega * (target - value) - 2.0 * dampingRatio * omega * velocity;
            velocity += acceleration * step;
            value += velocity * step;
        }
        return value;
    }

    public void reset(double value) {
        this.value = value;
        this.velocity = 0.0;
    }

    public double value() {
        return value;
    }

    public double velocity() {
        return velocity;
    }

    public void impulse(double velocityDelta) {
        velocity += velocityDelta;
    }

    public void clamp(double valueLimit, double velocityLimit) {
        if (valueLimit > 0.0) value = Math.max(-valueLimit, Math.min(valueLimit, value));
        if (velocityLimit > 0.0) velocity = Math.max(-velocityLimit, Math.min(velocityLimit, velocity));
    }
}
