package com.armaturemc.renderer.internal.pose;

import org.joml.Matrix4d;
import org.joml.Vector3d;

/** Converts Blockbench model-space pose matrices to ItemDisplay block-space transforms. */
public final class DisplaySpaceTransform {
    private static final double BLOCKBENCH_UNITS_PER_BLOCK = 16.0;

    public Matrix4d convert(Matrix4d modelMatrix, double geometryScale, double modelOffsetY) {
        if (!Double.isFinite(geometryScale) || geometryScale <= 0) {
            throw new IllegalArgumentException("geometryScale must be finite and positive");
        }
        if (!Double.isFinite(modelOffsetY)) throw new IllegalArgumentException("modelOffsetY must be finite");
        Matrix4d result = new Matrix4d(modelMatrix);
        Vector3d translation = result.getTranslation(new Vector3d()).div(BLOCKBENCH_UNITS_PER_BLOCK);
        result.setTranslation(translation.x, translation.y + modelOffsetY, translation.z);
        return result.scale(geometryScale);
    }
}
