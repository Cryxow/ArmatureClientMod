package com.armaturemc.renderer.internal.pose;

import java.util.Map;

/** Complete local pose and current bone visibility for one render sample. */
public record NativePose(Map<String, BonePose> bones, Map<String, Boolean> visibility) {
}
