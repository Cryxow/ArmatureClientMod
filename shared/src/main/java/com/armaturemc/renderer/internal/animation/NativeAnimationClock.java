package com.armaturemc.renderer.internal.animation;

/** Pure authored-time mapping for native loop, once, and hold playback. */
public final class NativeAnimationClock {
    public Sample sample(NativeAnimation animation, double elapsedSeconds, float speed) {
        if (animation == null || !Double.isFinite(elapsedSeconds) || elapsedSeconds < 0.0
            || !Float.isFinite(speed) || speed == 0.0F) {
            throw new IllegalArgumentException("Animation clock input must have finite elapsed time and non-zero speed");
        }
        double length = animation.lengthSeconds();
        if (!Double.isFinite(length) || length < 0.0) {
            throw new IllegalArgumentException("Animation length must be finite and non-negative");
        }
        double authored = elapsedSeconds * Math.abs((double) speed);
        boolean reverse = speed < 0.0F;
        return switch (animation.loopMode()) {
            case "loop" -> {
                if (length == 0.0) yield new Sample(0.0, false, authored, 0L);
                long loopCount = (long) Math.floor(authored / length);
                double phase = authored % length;
                double time = reverse ? length - phase : phase;
                yield new Sample(time, false, authored, loopCount);
            }
            case "hold" -> new Sample(reverse
                ? Math.max(length - authored, 0.0) : Math.min(authored, length),
                false, authored, 0L);
            case "once" -> new Sample(reverse
                ? Math.max(length - authored, 0.0) : Math.min(authored, length),
                authored >= length, authored, 0L);
            default -> throw new IllegalArgumentException("Unsupported animation loop mode '"
                + animation.loopMode() + "'");
        };
    }

    public record Sample(double timeSeconds, boolean complete,
                         double authoredSeconds, long loopCount) { }
}
