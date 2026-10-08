package com.armaturemc.renderer.internal.animation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Renderer-independent playback state for one native model session.
 *
 * <p>The loop is the priority-50 base layer and the action is the priority-100
 * overlay.  Packet transport and Bukkit are deliberately absent so delayed
 * ticks, restart semantics, and failed replacements can be tested exactly.</p>
 */
public final class NativeAnimationPlayback {
    public static final int LOOP_PRIORITY = 50;
    public static final int ACTION_PRIORITY = 100;

    private final Map<String, NativeAnimation> animations;
    private final Map<String, NativeAnimation> loopAnimations;
    private final Map<String, NativeAnimation> actionAnimations;
    private final LongSupplier clockNanos;
    private final NativeAnimationClock animationClock = new NativeAnimationClock();
    private String loop;
    private float loopSpeed = 1.0F;
    private long loopStartedNanos;
    private String action;
    private float actionSpeed = 1.0F;
    private long actionStartedNanos;

    public NativeAnimationPlayback(Map<String, NativeAnimation> animations) {
        this(animations, System::nanoTime);
    }

    public NativeAnimationPlayback(Map<String, NativeAnimation> animations, LongSupplier clockNanos) {
        Objects.requireNonNull(animations, "animations");
        this.animations = Map.copyOf(new LinkedHashMap<>(animations));
        Map<String, NativeAnimation> loopPlayback = new LinkedHashMap<>();
        Map<String, NativeAnimation> actionPlayback = new LinkedHashMap<>();
        this.animations.forEach((name, animation) -> {
            loopPlayback.put(name, animation.asLoopPlayback());
            actionPlayback.put(name, animation.asActionPlayback());
        });
        this.loopAnimations = Map.copyOf(loopPlayback);
        this.actionAnimations = Map.copyOf(actionPlayback);
        this.clockNanos = Objects.requireNonNull(clockNanos, "clockNanos");
    }

    /** Installs a loop; replaying the same clip at the same speed is idempotent. */
    public boolean playLoop(String animation, float speed) {
        if (!canPlay(animation, speed)) return false;
        if (Objects.equals(loop, animation) && Float.compare(loopSpeed, speed) == 0) return true;
        loop = animation;
        loopSpeed = speed;
        loopStartedNanos = clockNanos.getAsLong();
        return true;
    }

    /** Replaces an action atomically, restarting its clock even for the same clip. */
    public boolean playAction(String animation, float speed) {
        if (!canPlay(animation, speed)) return false;
        action = animation;
        actionSpeed = speed;
        actionStartedNanos = clockNanos.getAsLong();
        return true;
    }

    public void stopLoop(String animation) {
        if (Objects.equals(loop, animation)) loop = null;
    }

    public void stopAction(String animation) {
        if (Objects.equals(action, animation)) action = null;
    }

    public boolean isActionPlaying(String animation) {
        return Objects.equals(action, animation);
    }

    public boolean actionHoldsLastFrame(String animation) {
        NativeAnimation value = animation(animation);
        return value != null && "hold".equals(value.loopMode());
    }

    public int actionDurationTicks(String animation, float speed) {
        if (!Float.isFinite(speed) || speed == 0.0F) return 0;
        NativeAnimation value = animation(animation);
        return value == null ? 0 : Math.max(1,
            (int) Math.ceil(value.lengthSeconds() * 20.0 / Math.abs((double) speed)));
    }

    public String loopName() { return loop; }
    public String actionName() { return action; }
    public float loopSpeed() { return loopSpeed; }
    public float actionSpeed() { return actionSpeed; }

    /**
     * Computes BetterModel's modifier-style lerp weight from elapsed real time.
     * One tick is 50 ms; animation speed affects authored phase, not blend
     * duration.
     */
    public static double blendWeight(Clip clip, float speed, int startTicks, int endTicks) {
        return blendWeight(clip, speed, startTicks, endTicks, false);
    }

    /**
     * Same lerp with an optional session-start short circuit.
     *
     * <p>A clip's very first presentation has no previous pose to fade from, so
     * ramping its blend-in from the model rest pose would render the rest pose
     * for the whole blend-in window. {@code startAtFullWeight} starts that first
     * presentation at authored frame zero instead. Blend-out is unaffected.
     */
    public static double blendWeight(Clip clip, float speed, int startTicks, int endTicks,
                                     boolean startAtFullWeight) {
        if (clip == null || !Float.isFinite(speed) || speed == 0.0F) return 0.0;
        double speedMagnitude = Math.abs((double) speed);
        double elapsed = clip.authoredSeconds() / speedMagnitude;
        double blendIn = startAtFullWeight || startTicks <= 0 ? 1.0
            : Math.min(1.0, elapsed / (startTicks * 0.05));
        boolean finite = "once".equals(clip.animation().loopMode());
        if (!finite || endTicks <= 0) return blendIn;
        double remaining = clip.animation().lengthSeconds() / speedMagnitude - elapsed;
        double blendOut = Math.max(0.0, Math.min(1.0, remaining / (endTicks * 0.05)));
        return Math.min(blendIn, blendOut);
    }

    /** A pose-to-pose loop replacement already supplies the incoming fade. */
    public static double loopWeight(Clip clip, float speed, int startTicks, int endTicks,
                                    boolean replacingRenderedPose) {
        double weight = blendWeight(clip, speed, startTicks, endTicks);
        return replacingRenderedPose && clip != null && Float.isFinite(speed) && speed != 0.0F
            ? 1.0 : weight;
    }

    /** Samples both layers from one monotonic instant. */
    public Snapshot sample() {
        long now = clockNanos.getAsLong();
        Clip actionClip = sample(action, actionStartedNanos, actionSpeed, now, true);
        Clip loopClip = sample(loop, loopStartedNanos, loopSpeed, now, false);
        return new Snapshot(loopClip, actionClip);
    }

    /** Samples the incoming action's first authored frame without restarting its clock. */
    public Snapshot actionStart() {
        long now = clockNanos.getAsLong();
        return new Snapshot(sample(loop, loopStartedNanos, loopSpeed, now, false),
            sample(action, actionStartedNanos, actionSpeed, actionStartedNanos, true));
    }

    /** An action replacement must not apply its incoming fade twice. */
    public static double actionWeight(Clip clip, float speed, int startTicks, int endTicks,
                                      boolean replacingRenderedPose) {
        // The pose transition supplies blend-in, but a short action may already
        // be in blend-out before that transition finishes. Retain that fade.
        return blendWeight(clip, speed, startTicks, endTicks, replacingRenderedPose);
    }

    /** Clears only the playback instance which produced the completed sample. */
    public void complete(Snapshot snapshot) {
        if (snapshot == null) return;
        if (snapshot.action() != null && snapshot.action().complete()
                && Objects.equals(action, snapshot.action().name())) action = null;
        if (snapshot.action() == null && snapshot.loop() != null && snapshot.loop().complete()
                && Objects.equals(loop, snapshot.loop().name())) loop = null;
    }

    public State state() {
        return new State(loop, loopSpeed, loopStartedNanos, action, actionSpeed, actionStartedNanos);
    }

    public void restore(State state) {
        if (state == null) return;
        if (state.loop() != null) requirePlayable(state.loop(), state.loopSpeed());
        if (state.action() != null) requirePlayable(state.action(), state.actionSpeed());
        loop = state.loop();
        loopSpeed = state.loopSpeed();
        loopStartedNanos = state.loopStartedNanos();
        action = state.action();
        actionSpeed = state.actionSpeed();
        actionStartedNanos = state.actionStartedNanos();
    }

    private Clip sample(String name, long startedNanos, float speed, long nowNanos,
                        boolean actionLayer) {
        if (name == null) return null;
        NativeAnimation animation = animations.get(name);
        if (animation == null) return null;
        if (actionLayer) {
            animation = actionAnimations.get(name);
        } else {
            animation = loopAnimations.get(name);
        }
        double elapsed = Math.max(0.0, (nowNanos - startedNanos) / 1_000_000_000.0);
        NativeAnimationClock.Sample sample = animationClock.sample(animation, elapsed, speed);
        return new Clip(name, animation, sample.timeSeconds(), sample.complete(),
            sample.authoredSeconds(), sample.loopCount());
    }

    private NativeAnimation animation(String name) {
        return name == null ? null : animations.get(name);
    }

    private void requirePlayable(String animation, float speed) {
        if (!canPlay(animation, speed)) {
            throw new IllegalArgumentException("Invalid native animation playback: " + animation);
        }
    }

    private boolean canPlay(String animation, float speed) {
        if (animation == null || !animations.containsKey(animation)) return false;
        if (!Float.isFinite(speed) || speed == 0.0F) {
            return false;
        }
        return true;
    }

    public record Snapshot(Clip loop, Clip action) { }

    public record Clip(String name, NativeAnimation animation, double timeSeconds, boolean complete,
                       double authoredSeconds, long loopCount) { }

    public record State(String loop, float loopSpeed, long loopStartedNanos,
                        String action, float actionSpeed, long actionStartedNanos) { }
}
