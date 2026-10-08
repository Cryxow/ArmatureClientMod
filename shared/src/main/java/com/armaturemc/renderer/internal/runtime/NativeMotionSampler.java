package com.armaturemc.renderer.internal.runtime;

import com.armaturemc.core.motion.DampedSpring;
import com.armaturemc.renderer.internal.animation.MolangContext;
import com.armaturemc.renderer.api.ArmatureMotionSettings;
import com.armaturemc.renderer.api.ArmatureSwaySettings;
import com.armaturemc.renderer.api.RenderMotionInput;
import java.util.function.LongSupplier;
import org.joml.Matrix4d;

/**
 * Platform-free Armature motion solver. The server adapter runs on the player's
 * scheduler; Fabric uses the same springs on its render thread with rate-normalized input.
 */
public class NativeMotionSampler {
    public record Input(double x, double z, double yaw, double pitch) { }
    private static final double MAX_DELTA_SECONDS = 0.25;
    /** Nominal server tick, used until a real interval has been observed. */
    private static final double DEFAULT_TICK_SECONDS = 0.05;
    /** Minimum accepted smoothed tick, guarding against a stalled clock. */
    private static final double MIN_TICK_SECONDS = 1.0e-3;
    /** Smoothing rate for the tick interval, ~0.25 s time constant. */
    private static final double TICK_INTERVAL_SMOOTHING = 4.0;
    private ArmatureMotionSettings settings;
    private boolean initialized;
    private long lastNanos;
    private long lastCameraNanos;
    private double lastYaw;
    private double lastPitch;
    private double lastX;
    private double lastZ;
    private double lookYaw;
    private double lookPitch;
    private double lookRoll;
    private double moveYaw;
    private double movePitch;
    private double moveRoll;
    private double bobX;
    private double bobY;
    private double bobRoll;
    private double cameraX;
    private double cameraY;
    private double cameraZ;
    private double phase;
    private boolean proceduralBob = true;
    private boolean renderFrames;
    private DampedSpring bobXSpring;
    private DampedSpring bobYSpring;
    private DampedSpring bobRollSpring;
    private DampedSpring cameraXSpring;
    private DampedSpring cameraYSpring;
    private DampedSpring cameraZSpring;
    private double lastVelocityX;
    private double lastVelocityZ;
    /**
     * Smoothed tick interval used by the physics derivative. The real
     * wall-clock interval between two captures jitters by one or two percent
     * on a live server, and a steady walk delivers an almost constant
     * displacement per tick, so {@code distance / instantaneousDt} wobbles
     * by the same percentage: at 4.3 blocks/s that is roughly ±0.9 blocks/s²
     * of phantom acceleration — above the physics deadband and of random
     * sign, which is what kept the bone micro-rotating during a constant
     * walk. Differentiating against this smoothed interval instead measures
     * the player's actual speed change.
     */
    private double nominalTickSeconds = DEFAULT_TICK_SECONDS;
    /** Nanosecond clock; injectable so tests can drive captures deterministically. */
    private final LongSupplier nanoClock;
    private PhysicsSnapshot physicsSnapshot = PhysicsSnapshot.IDLE;

    /**
     * Immutable world-frame physics input snapshot derived from the latest
     * capture. Accelerations and velocities are in world axes (blocks/s²,
     * blocks/s); {@code yawRadians} is the entity body yaw and
     * {@code pitchRadians} the absolute camera pitch (Minecraft convention:
     * positive looks down) so physics can map world vectors into the model
     * frame and compensate the camera's up/down rotation.
     */
    public record PhysicsSnapshot(double dtSeconds, double forwardAccelX, double forwardAccelZ,
                           double velocityX, double velocityZ, double lateralTurn,
                           double yawRadians, double pitchRadians) {
        public static final PhysicsSnapshot IDLE =
            new PhysicsSnapshot(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    }

    public NativeMotionSampler(ArmatureMotionSettings settings) {
        this(settings, System::nanoTime);
    }

    public NativeMotionSampler(ArmatureMotionSettings settings, LongSupplier nanoClock) {
        this.settings = settings == null ? ArmatureMotionSettings.defaults() : settings;
        this.nanoClock = nanoClock == null ? System::nanoTime : nanoClock;
        resetLayers(this.settings);
    }

    public synchronized void settings(ArmatureMotionSettings next) {
        if (next == null || next.equals(settings)) return;
        settings = next;
        resetLayers(next);
    }

    /** Local render frames use rates, so gain and physics do not depend on the display refresh rate. */
    public synchronized void renderFrames(boolean enabled) { renderFrames = enabled; }

    public synchronized void capture(Input location, RenderMotionInput input) {
        if (location == null || input == null) return;
        long now = nanoClock.getAsLong();
        if (!initialized) {
            initialized = true;
            lastNanos = now;
            lastYaw = location.yaw();
            lastPitch = location.pitch();
            lastX = location.x();
            lastZ = location.z();
            return;
        }
        double dt = Math.min(MAX_DELTA_SECONDS, Math.max(0.0,
            (now - lastNanos) / 1_000_000_000.0));
        lastNanos = now;
        if (dt <= 0.0) return;
        nominalTickSeconds = lerp(nominalTickSeconds, dt,
            alpha(TICK_INTERVAL_SMOOTHING, dt));
        double yawDelta = input.hasCameraDelta() ? input.yawDelta()
            : normalizeAngle(location.yaw() - lastYaw);
        double pitchDelta = input.hasCameraDelta() ? input.pitchDelta()
            : location.pitch() - lastPitch;
        double dx = location.x() - lastX;
        double dz = location.z() - lastZ;
        double distance = Math.sqrt(dx * dx + dz * dz);
        double yaw = Math.toRadians(location.yaw());
        double strafe = (dx * Math.cos(yaw) - dz * Math.sin(yaw)) / dt;
        double forward = -(dx * Math.sin(yaw) + dz * Math.cos(yaw)) / dt;
        double inputLength = Math.hypot(input.strafeAxis(), input.forwardAxis());
        if (inputLength > 0.0 && distance > 0.0001) {
            double speed = distance / dt;
            strafe = -input.strafeAxis() / inputLength * speed;
            forward = -input.forwardAxis() / inputLength * speed;
        }
        if (input.hasCameraDelta()) {
            updateMovement(strafe, forward, dt);
        } else {
            double rate = renderFrames ? DEFAULT_TICK_SECONDS / dt : 1;
            updateLook(yawDelta * rate, pitchDelta * rate, dt);
            updateMovement(strafe, forward, dt);
            updateCamera(location.pitch(), yawDelta * rate, pitchDelta * rate, dt);
        }
        updateBob(distance, strafe, dt);
        updatePhysics(dt, dx, dz, yawDelta, location.yaw(), location.pitch());
        lastYaw = location.yaw();
        lastPitch = location.pitch();
        lastX = location.x();
        lastZ = location.z();
        finiteOrReset();
    }

    public synchronized void captureCameraDelta(double yawDelta, double pitchDelta, double absolutePitch) {
        long now = nanoClock.getAsLong();
        if (lastCameraNanos == 0L) {
            lastCameraNanos = now;
            return;
        }
        double dt = Math.min(MAX_DELTA_SECONDS, Math.max(0.0,
            (now - lastCameraNanos) / 1_000_000_000.0));
        lastCameraNanos = now;
        if (dt <= 0.0) return;
        updateLook(yawDelta, pitchDelta, dt);
        updateCamera(absolutePitch, yawDelta, pitchDelta, dt);
        finiteOrReset();
    }

    public synchronized void proceduralBob(boolean enabled) { proceduralBob = enabled; }

    /** Latest physics input signals; captured once per motion capture. */
    public synchronized PhysicsSnapshot physicsSnapshot() {
        return physicsSnapshot;
    }

    /**
     * Viewer state for Molang keyframes, read from the last capture.
     *
     * <p>This is the only path that exposes live yaw/pitch to animation sampling,
     * and it stays on the capture values rather than reading the Bukkit entity, so
     * it is safe to call from the render loop on any thread.
     */
    public synchronized MolangContext molangContext() {
        PhysicsSnapshot snapshot = physicsSnapshot;
        double speed = Math.hypot(snapshot.velocityX(), snapshot.velocityZ());
        if (!Double.isFinite(speed)) speed = 0.0;
        return MolangContext.defaults()
            .withViewer(lastYaw, lastPitch)
            .withMotion(speed * snapshot.dtSeconds(), speed, speed > MIN_MOVEMENT_DELTA);
    }

    public synchronized Matrix4d matrix() {
        return matrix(null);
    }

    public synchronized Matrix4d matrix(String boneName) {
        return matrix("s_camera".equals(boneName) || "camera".equals(boneName)
            || "__armature_camera_marker".equals(boneName));
    }

    public synchronized Matrix4d matrix(boolean cameraBone) {
        boolean applySway = !cameraBone || settings.sway().affectsCameraBone();
        double pitch = applySway ? movePitch + lookPitch : 0.0;
        double yaw = applySway ? moveYaw + lookYaw : 0.0;
        double roll = (applySway ? moveRoll + lookRoll : 0.0) + bobRoll;
        return new Matrix4d().translate(bobX + cameraX, bobY + cameraY, cameraZ)
            .rotateXYZ(Math.toRadians(pitch), Math.toRadians(yaw), Math.toRadians(roll));
    }

    private void updateLook(double yawDelta, double pitchDelta, double dt) {
        ArmatureSwaySettings sway = settings.sway();
        double max = sway.maximumRotation();
        lookYaw = lerp(lookYaw, sway.enabled() ? clamp(-yawDelta * sway.lookYawGain(), max) : 0.0,
            alpha(sway.lookLerpSpeed(), dt));
        lookPitch = lerp(lookPitch, sway.enabled() ? clamp(-pitchDelta * sway.lookPitchGain(), max) : 0.0,
            alpha(sway.lookLerpSpeed(), dt));
        lookRoll = lerp(lookRoll, sway.enabled() ? clamp(yawDelta * sway.lookRollGain(), max) : 0.0,
            alpha(sway.lookLerpSpeed(), dt));
    }

    private void updateMovement(double strafe, double forward, double dt) {
        ArmatureSwaySettings sway = settings.sway();
        double max = sway.maximumRotation();
        moveYaw = lerp(moveYaw, sway.enabled() ? clamp(-strafe * sway.movementYawGain(), max) : 0.0,
            alpha(sway.movementLerpSpeed(), dt));
        movePitch = lerp(movePitch, sway.enabled() ? clamp(forward * sway.movementPitchGain(), max) : 0.0,
            alpha(sway.movementLerpSpeed(), dt));
        moveRoll = lerp(moveRoll, sway.enabled() ? clamp(-strafe * sway.movementRollGain(), max) : 0.0,
            alpha(sway.movementLerpSpeed(), dt));
    }

    /**
     * Builds the physics snapshot from the <b>world-frame</b> velocity: the
     * movement signal is the tangential acceleration — the rate of change of
     * speed along the direction of travel. Differencing the body-frame
     * projections instead (previous behavior) leaked the rotation of the
     * body frame itself into the signal: even at constant world velocity a
     * yaw change shifted the strafe/forward split by speed x yaw-rate, so
     * the movement tilt depended on the player's orientation. The tangential
     * form makes the tilt depend only on how fast the player speeds up or
     * slows down, whatever their heading; NativeBonePhysics projects the
     * world vector onto the body frame to apply it on the local axes.
     *
     * <p>Two guards keep a steady walk perfectly still:</p>
     * <ul>
     * <li>Displacements below {@link #MIN_MOVEMENT_DELTA} blocks (spawn
     *     settling, interpolation noise, a client packet that carried no
     *     movement) are ignored and the previous velocity is held, so they
     *     cannot fake an acceleration.</li>
     * <li>Speed and its derivative are measured against the smoothed
     *     {@link #nominalTickSeconds} rather than the instantaneous
     *     wall-clock interval, whose per-tick jitter otherwise turned a
     *     perfectly constant walk into a stream of random-sign phantom
     *     accelerations above the physics deadband.</li>
     * </ul>
     */
    private void updatePhysics(double dt, double deltaX, double deltaZ,
                               double yawDelta, double bodyYaw,
                               double cameraPitchDegrees) {
        // Displacement is the raw, clock-independent distance covered since
        // the previous capture; only the conversion to a rate uses the
        // smoothed tick interval.
        double displacement = Math.hypot(deltaX, deltaZ);
        double tick = renderFrames ? dt : nominalTickSeconds >= MIN_TICK_SECONDS
            ? nominalTickSeconds : DEFAULT_TICK_SECONDS;
        double velocityX;
        double velocityZ;
        if (displacement <= (renderFrames ? MIN_MOVEMENT_DELTA * dt / DEFAULT_TICK_SECONDS : MIN_MOVEMENT_DELTA)) {
            // No meaningful travel this tick: keep the previous velocity so
            // the tangential derivative sees no change.
            velocityX = renderFrames ? 0 : lastVelocityX;
            velocityZ = renderFrames ? 0 : lastVelocityZ;
        } else {
            velocityX = deltaX / tick;
            velocityZ = deltaZ / tick;
        }
        double speed = Math.hypot(velocityX, velocityZ);
        double lastSpeed = Math.hypot(lastVelocityX, lastVelocityZ);
        double tangential = (speed - lastSpeed) / tick;
        double scale = speed > 1.0e-6 ? tangential / speed : 0.0;
        double accelX = velocityX * scale;
        double accelZ = velocityZ * scale;
        double yawRadians = Math.toRadians(bodyYaw);
        double lateralTurn = clamp(yawDelta / tick, 720.0);
        physicsSnapshot = new PhysicsSnapshot(dt, accelX, accelZ, velocityX, velocityZ,
            lateralTurn, yawRadians, Math.toRadians(cameraPitchDegrees));
        lastVelocityX = velocityX;
        lastVelocityZ = velocityZ;
    }

    /**
     * World-position delta below which we treat the tick as not moving; this
     * suppresses the tiny per-tick position differences that Minecraft
     * delivers during spawn settling / interpolation jitter / occasional
     * micro-teleport, so a steady walk does not flicker the bone. The value
     * is smaller than one Minecraft block, so any genuine step in a walk is
     * still recognized.
     */
    private static final double MIN_MOVEMENT_DELTA = 0.02;

    private void updateCamera(double pitch, double yawDelta, double pitchDelta, double dt) {
        ArmatureMotionSettings.CameraFollow camera = settings.cameraFollow();
        double max = camera.maximumOffset();
        double targetX = camera.enabled() ? clamp(-yawDelta * camera.yawPositionGain(), max) : 0.0;
        double compensation = camera.enabled()
            ? -(Math.min(90.0, Math.abs(pitch)) / 90.0) * camera.pitchCompensation() : 0.0;
        double radians = Math.toRadians(pitch);
        double targetY = camera.enabled()
            ? clamp(-pitchDelta * camera.pitchPositionGain() + compensation * Math.cos(radians),
                max + camera.pitchCompensation()) : 0.0;
        double targetZ = camera.enabled()
            ? clamp(compensation * Math.sin(radians), max + camera.pitchCompensation()) : 0.0;
        cameraX = cameraXSpring.update(targetX, dt);
        cameraY = cameraYSpring.update(targetY, dt);
        cameraZ = cameraZSpring.update(targetZ, dt);
    }

    private void updateBob(double distance, double strafe, double dt) {
        ArmatureMotionSettings.Bob bob = settings.bob();
        if (proceduralBob && bob.enabled()) phase += distance * bob.cyclesPerBlock() * Math.PI * 2.0;
        double moving = proceduralBob && bob.enabled() && distance > 0.0001 ? 1.0 : 0.0;
        bobX = bobXSpring.update(Math.sin(phase) * bob.horizontalAmplitude() * moving, dt);
        bobY = bobYSpring.update(-Math.abs(Math.cos(phase)) * bob.verticalAmplitude() * moving, dt);
        bobRoll = bobRollSpring.update(
            clamp(-strafe * bob.rollAmplitude(), bob.rollAmplitude()) * moving, dt);
    }

    private void finiteOrReset() {
        if (Double.isFinite(lookYaw) && Double.isFinite(lookPitch) && Double.isFinite(lookRoll)
            && Double.isFinite(moveYaw) && Double.isFinite(movePitch) && Double.isFinite(moveRoll)
            && Double.isFinite(bobX) && Double.isFinite(bobY) && Double.isFinite(bobRoll)
            && Double.isFinite(cameraX) && Double.isFinite(cameraY) && Double.isFinite(cameraZ)
            && Double.isFinite(physicsSnapshot.forwardAccelX())
            && Double.isFinite(physicsSnapshot.forwardAccelZ())
            && Double.isFinite(physicsSnapshot.velocityX())
            && Double.isFinite(physicsSnapshot.velocityZ())
            && Double.isFinite(physicsSnapshot.lateralTurn())
            && Double.isFinite(physicsSnapshot.yawRadians())
            && Double.isFinite(physicsSnapshot.pitchRadians())) return;
        lookYaw = lookPitch = lookRoll = moveYaw = movePitch = moveRoll = 0.0;
        bobX = bobY = bobRoll = cameraX = cameraY = cameraZ = 0.0;
        physicsSnapshot = PhysicsSnapshot.IDLE;
        lastVelocityX = 0.0;
        lastVelocityZ = 0.0;
        resetSprings();
    }

    private void resetLayers(ArmatureMotionSettings next) {
        phase = 0.0;
        bobXSpring = spring(next.bob().frequency(), next.bob().damping());
        bobYSpring = spring(next.bob().frequency(), next.bob().damping());
        bobRollSpring = spring(next.bob().frequency(), next.bob().damping());
        cameraXSpring = spring(next.cameraFollow().frequency(), next.cameraFollow().damping());
        cameraYSpring = spring(next.cameraFollow().frequency(), next.cameraFollow().damping());
        cameraZSpring = spring(next.cameraFollow().frequency(), next.cameraFollow().damping());
        resetSprings();
    }

    private void resetSprings() {
        if (bobXSpring == null) return;
        bobXSpring.reset(0.0);
        bobYSpring.reset(0.0);
        bobRollSpring.reset(0.0);
        cameraXSpring.reset(0.0);
        cameraYSpring.reset(0.0);
        cameraZSpring.reset(0.0);
    }

    private static DampedSpring spring(double frequency, double damping) {
        return new DampedSpring(Math.max(0.0, frequency), Math.max(0.0, damping));
    }

    private static double alpha(double speed, double dt) {
        if (!Double.isFinite(speed) || speed <= 0.0) return 1.0;
        return 1.0 - Math.exp(-speed * Math.max(0.0, dt));
    }
    private static double lerp(double current, double target, double a) { return current + (target - current) * a; }
    private static double clamp(double value, double max) { return Math.max(-max, Math.min(max, value)); }
    private static double normalizeAngle(double value) {
        value %= 360.0;
        return value > 180.0 ? value - 360.0 : value < -180.0 ? value + 360.0 : value;
    }
}
