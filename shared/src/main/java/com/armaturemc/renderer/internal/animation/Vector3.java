package com.armaturemc.renderer.internal.animation;

/** Immutable numeric vector used by native animation, independent of Bukkit/JOML. */
public record Vector3(double x, double y, double z) {
    public Vector3 {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Animation vector must be finite");
        }
    }

    public Vector3 add(Vector3 other) { return new Vector3(x + other.x, y + other.y, z + other.z); }
    public Vector3 subtract(Vector3 other) { return new Vector3(x - other.x, y - other.y, z - other.z); }
    public Vector3 multiply(double value) { return new Vector3(x * value, y * value, z * value); }
    public static Vector3 lerp(Vector3 from, Vector3 to, double progress) {
        return from.add(to.subtract(from).multiply(progress));
    }

    /**
     * Whether every component is finite. The constructor rejects non-finite
     * input, so this is for values produced elsewhere, such as a Molang result,
     * before it is wrapped.
     */
    public boolean isFinite() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }
}
