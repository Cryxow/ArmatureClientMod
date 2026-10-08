package com.armaturemc.renderer.internal.compile;

import java.util.Locale;

/** Explicit v2 roles. CAMERA_MARKER is compiler-owned and forbidden in source documents. */
public enum ArmatureBoneRole {
    NONE, HEAD, BODY, RIGHT_FULL_ARM, LEFT_FULL_ARM, RIGHT_ARM, LEFT_ARM, RIGHT_FOREARM, LEFT_FOREARM, RIGHT_FULL_LEG, LEFT_FULL_LEG, RIGHT_THIGH, LEFT_THIGH, RIGHT_LOWER_LEG, LEFT_LOWER_LEG, ITEM_RIGHT, ITEM_LEFT, CAMERA, CAMERA_MARKER;

    public String serialized() { return name().toLowerCase(Locale.ROOT); }

    public static ArmatureBoneRole parse(String value) {
        try { return valueOf(value.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown armature_role '" + value + "'", exception);
        }
    }

    public boolean rightHand() {
        return this == RIGHT_FULL_ARM || this == RIGHT_ARM || this == RIGHT_FOREARM || this == ITEM_RIGHT;
    }
    public boolean leftHand() {
        return this == LEFT_FULL_ARM || this == LEFT_ARM || this == LEFT_FOREARM || this == ITEM_LEFT;
    }
}
