package com.armaturemc.client;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;

/** A view-space rotation which preserves the player's perspective and FOV. */
public record ClientCameraEffect(Quaternionf viewRotation) {
    public static final ClientCameraEffect IDENTITY = new ClientCameraEffect(new Quaternionf());

    public ClientCameraEffect {
        viewRotation = new Quaternionf(viewRotation).normalize();
        if (!viewRotation.isFinite())
            throw new IllegalArgumentException("Invalid camera effect");
    }

    @Override public Quaternionf viewRotation() { return new Quaternionf(viewRotation); }

    /** (C * inverse(Q))^-1 = Q * C^-1: moves the actual render camera by the former view-space effect. */
    public Quaternionf orientation(Quaternionfc vanilla) {
        return new Quaternionf(vanilla).mul(new Quaternionf(viewRotation).conjugate()).normalize();
    }

    public boolean rotates() {
        return Math.abs(viewRotation.x) + Math.abs(viewRotation.y) + Math.abs(viewRotation.z) > 1e-7f;
    }

    /** Camera-attached hands have no world view matrix, so apply their visual effect once here. */
    public Matrix4f handTransform() { return new Matrix4f().rotate(viewRotation); }

    /** Retain an ordinary perspective matrix; Iris shaderpacks can reconstruct view-space depth. */
    public Matrix4f projection(Matrix4f vanilla) { return new Matrix4f(vanilla); }

    public static float yaw(Vector3f forward, float fallback) {
        return Math.hypot(forward.x, forward.z) < 1e-6 ? fallback
            : (float)Math.toDegrees(Math.atan2(-forward.x, forward.z));
    }

    public static float pitch(Vector3f forward) {
        return (float)Math.toDegrees(Math.asin(Math.clamp(-forward.y, -1f, 1f)));
    }
}
