package com.armaturemc.renderer.internal.pose;

import java.util.Objects;

/** Deterministic local-pose transition used when a clip is replaced or stopped. */
public final class NativePoseTransition {
    private final NativeAnimationMixer mixer;
    private final NativePose from;
    private final long startedNanos;
    private final long durationNanos;

    public NativePoseTransition(NativePose from, long startedNanos, long durationNanos) {
        mixer = new NativeAnimationMixer();
        this.from = Objects.requireNonNull(from, "from");
        if (startedNanos < 0L) throw new IllegalArgumentException("startedNanos cannot be negative");
        if (durationNanos < 0L) throw new IllegalArgumentException("durationNanos cannot be negative");
        this.startedNanos = startedNanos;
        this.durationNanos = durationNanos;
    }

    public NativePose sample(NativePose target, long nowNanos) {
        Objects.requireNonNull(target, "target");
        if (nowNanos < startedNanos) return from;
        if (durationNanos == 0L) return target;
        double weight = Math.min(1.0, (nowNanos - startedNanos) / (double) durationNanos);
        return mixer.blend(from, target, weight);
    }

    public boolean complete(long nowNanos) {
        return nowNanos >= startedNanos + durationNanos;
    }

    public NativePose from() { return from; }
    public long startedNanos() { return startedNanos; }
    public long durationNanos() { return durationNanos; }
}
