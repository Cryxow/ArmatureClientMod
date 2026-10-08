package com.armaturemc.core.animation;

public record AdditivePose(double x, double y, double z,
                           double qx, double qy, double qz, double qw) {
    public AdditivePose {
        if (!finite(x) || !finite(y) || !finite(z)
            || !finite(qx) || !finite(qy) || !finite(qz) || !finite(qw)) {
            throw new IllegalArgumentException("additive pose values must be finite");
        }
    }

    public static AdditivePose identity() {
        return new AdditivePose(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0);
    }

    private static boolean finite(double value) {
        return Double.isFinite(value);
    }
}
