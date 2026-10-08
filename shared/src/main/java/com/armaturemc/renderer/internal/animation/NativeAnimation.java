package com.armaturemc.renderer.internal.animation;

import java.util.Map;
import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;

/** Parsed numeric animation tracks keyed by Blockbench bone UUID. */
public record NativeAnimation(String name, double lengthSeconds, String loopMode, boolean override,
                              Map<String, TransformTracks> bones, List<TimelineEvent> timeline) {
    public NativeAnimation {
        if (name == null || name.isBlank() || !Double.isFinite(lengthSeconds) || lengthSeconds < 0.0) {
            throw new IllegalArgumentException("Animation name and finite non-negative length are required");
        }
        if (loopMode == null || !java.util.Set.of("loop", "once", "hold").contains(loopMode)) {
            throw new IllegalArgumentException("Unsupported animation loop mode: " + loopMode);
        }
        bones = Collections.unmodifiableMap(new LinkedHashMap<>(bones));
        timeline = List.copyOf(timeline);
    }

    public record TransformTracks(AnimationTrack position, AnimationTrack rotation, AnimationTrack scale) {
    }

    /** BetterModel treats every non-hold action as a one-shot iterator. */
    public NativeAnimation asActionPlayback() {
        return "loop".equals(loopMode)
            ? new NativeAnimation(name, lengthSeconds, "once", override, bones, timeline) : this;
    }

    /** BetterModel's loop modifier repeats the selected clip regardless of its authored mode. */
    public NativeAnimation asLoopPlayback() {
        return "loop".equals(loopMode)
            ? this : new NativeAnimation(name, lengthSeconds, "loop", override, bones, timeline);
    }

    public record TimelineEvent(double time, String script) {
        public TimelineEvent {
            if (!Double.isFinite(time) || time < 0.0 || script == null || script.isBlank()) {
                throw new IllegalArgumentException("Timeline event needs a finite time and script");
            }
        }
    }
}
