package com.armaturemc.renderer.internal.bbmodel;

/** Bounded input budget for one Blockbench candidate. */
public record NativeModelLimits(int maxJsonBytes, int maxGroups, int maxCubes,
                                int maxAnimations, int maxKeyframes, int maxTextures,
                                int maxHierarchyDepth, int maxTextureBytes,
                                int maxTextureWidth, int maxTextureHeight) {
    public NativeModelLimits {
        if (maxJsonBytes <= 0 || maxGroups <= 0 || maxCubes <= 0 || maxAnimations <= 0
            || maxKeyframes <= 0 || maxTextures <= 0 || maxHierarchyDepth <= 0
            || maxTextureBytes <= 0 || maxTextureWidth <= 0 || maxTextureHeight <= 0) {
            throw new IllegalArgumentException("Native model limits must be positive");
        }
    }

    public static NativeModelLimits defaults() {
        return new NativeModelLimits(
            32 * 1024 * 1024,
            512,
            4096,
            // Includes generated offhand variants of authored animations.
            1024,
            131072,
            512,
            64,
            16 * 1024 * 1024,
            4096,
            4096);
    }
}
