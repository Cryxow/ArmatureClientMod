package com.armaturemc.renderer.internal.pose;

import com.armaturemc.renderer.internal.animation.Vector3;

/** Local position, Euler rotation and scale before hierarchy composition. */
public record BonePose(Vector3 position, Vector3 rotation, Vector3 scale) {
    public static final BonePose IDENTITY = new BonePose(new Vector3(0, 0, 0),
        new Vector3(0, 0, 0), new Vector3(1, 1, 1));
}
