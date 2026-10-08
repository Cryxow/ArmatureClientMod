package com.armaturemc.renderer.internal.pose;

import org.joml.Matrix4d;

/** World-space matrix for one bone after deterministic hierarchy composition. */
public record WorldBonePose(Matrix4d matrix, boolean visible) {
    public WorldBonePose {
        matrix = new Matrix4d(matrix);
    }
}
