package com.armaturemc.renderer.internal.runtime;

import com.armaturemc.core.motion.DampedSpring;
import com.armaturemc.renderer.api.ArmaturePhysicsSettings;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Per-session state of explicitly flagged or legacy {@code phy_} physics bones.
 *
 * <p><b>Reference is the playing animation.</b> The bone's rotation in the
 * current animation is what the bone should have while the player stands
 * still with a level camera. At rest — no acceleration, no turn, camera
 * pitch zero — the applied physics delta is exactly the identity, so the
 * authored animation plays untouched.</p>
 *
 * <p><b>Fixed local axes.</b> Isolated springs hold tilt as degrees about
 * the bone's <b>own local axes</b> — the axes the author sees on the bone in
 * Blockbench (up to the parser's sign mapping). The delta handed to the
 * composed pose is exactly {@code Rx(pitch) * Rz(roll)} built from
 * the spring values: it never depends on the player's world rotation, and
 * isolated bones need no conjugation or Euler re-decomposition, so motion stays
 * stable even at the camera's extreme up/down pitch.</p>
 *
 * <p>Movement mapping (in Blockbench bone axes): the X axis carries the
 * strafe-acceleration swing and the camera's left/right (yaw) turn swing;
 * the Z axis carries the forward/backward acceleration swing and the
 * camera's up/down (pitch) motion; the isolated Y axis is untouched. Camera pitch
 * drives the Z axis one-to-one and is never clamped by
 * {@code maximumAngle}, since a full up/down look must be followable;
 * the acceleration and turn terms are clamped. A non-finite snapshot
 * resets the springs, mirroring the motion state's finite guard.</p>
 *
 * <p><b>Chains.</b> A descendant's local target removes physics already
 * inherited through its parent's authored/animated axes. Its own damped
 * rotation-vector springs provide secondary motion while the hierarchy
 * retains the parent's rotation and pivot displacement. Unflagged helper
 * bones propagate this frame without receiving a spring.</p>
 */
public final class NativeBonePhysics {
    private static final double MAX_DT_SECONDS = 0.25;
    private static final double MAX_ACCELERATION = 80.0;
    private static final double MAX_PITCH_RADIANS = Math.PI;
    /** Tilt (degrees) per unit of acceleration. */
    private static final double DEGREES_PER_UNIT = 6.0;

    /** Below this body-frame acceleration magnitude, movement tilt is treated as zero
     * so constant-speed movement produces no bone rotation and minor per-frame jitter
     * is ignored. */
    private static final double ACCEL_THRESHOLD = 0.6;

    private final ReentrantLock lock = new ReentrantLock();
    private ArmaturePhysicsSettings settings;
    private final Map<String, Swing> bones = new LinkedHashMap<>();
    /** Snapshot the springs were last advanced with; render frames re-deliver
     * the same instance between captures and must not advance them again. */
    private NativeMotionSampler.PhysicsSnapshot lastSnapshot;

    public NativeBonePhysics(ArmaturePhysicsSettings settings) {
        this.settings = settings == null ? ArmaturePhysicsSettings.off() : settings;
    }

    /** Replaces the settings and clears accumulated spring state. */
    public void settings(ArmaturePhysicsSettings next) {
        if (next == null) next = ArmaturePhysicsSettings.off();
        lock.lock();
        try {
            if (next.equals(settings)) return;
            settings = next;
            resetLocked();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Advances every tracked bone. Springs chase the body-frame tilt implied
     * by the snapshot: the camera pitch drives the roll/Z channel directly
     * (never clamped by {@code maximumAngle}, since a full up/down look must
     * be compensable), acceleration tips the pitch/roll channels, and yaw
     * turning swings the pitch/X channel scaled by {@code turnGain} (small by
     * default: mouse sway while pitching must not fling the bone). The step
     * size is the snapshot's motion interval, and the springs only advance
     * when a <b>new</b> snapshot arrives: the render loop re-delivers the
     * same snapshot instance every frame between captures, so the dedup
     * keeps the spring time equal to real time. A non-finite snapshot
     * resets the springs, mirroring the motion state's finite guard.
     */
    public void update(Set<String> boneIds, NativeMotionSampler.PhysicsSnapshot snapshot) {
        update(boneIds, Map.of(), Map.of(), snapshot);
    }

    /**
     * Advances parents before children, including unflagged intermediate bones.
     * Local frames contain authored/animated/additive rotations without physics.
     * Each child chases the residual target after inherited physics, rather than
     * adding another copy of the camera/movement tilt. Quaternion conjugation
     * carries ancestor physics through rotated local frames; relative springs
     * use rotation vectors so simulation does not cross Euler singularities.
     */
    public void update(Set<String> boneIds, Map<String, String> parentIds,
                Map<String, Quaterniond> localFrames, NativeMotionSampler.PhysicsSnapshot snapshot) {
        if (boneIds == null || boneIds.isEmpty() || !settings.enabled()) return;
        lock.lock();
        try {
            if (!isFinite(snapshot)) {
                resetLocked();
                return;
            }
            if (snapshot == lastSnapshot) return;
            lastSnapshot = snapshot;
            double dt = Math.min(MAX_DT_SECONDS, Math.max(0.0, snapshot.dtSeconds()));
            if (dt <= 0.0) return;
            double max = settings.maximumAngle();
            double yawRadians = clamp(snapshot.yawRadians(), 2.0 * Math.PI);
            double cameraPitchDegrees =
                Math.toDegrees(clamp(snapshot.pitchRadians(), MAX_PITCH_RADIANS));
            // World-frame acceleration re-expressed in the body frame:
            // forward along the facing axis, strafe left(+)/right(−).
            double cos = Math.cos(yawRadians);
            double sin = Math.sin(yawRadians);
            double accelX = clamp(snapshot.forwardAccelX(), MAX_ACCELERATION);
            double accelZ = clamp(snapshot.forwardAccelZ(), MAX_ACCELERATION);
            double forwardAccel = -accelX * sin + accelZ * cos;
            double strafeAccel = accelX * cos + accelZ * sin;
            // The bone reacts <b>opposite</b> to the movement, so both movement
            // terms are negated: accelerating forward tilts the bone backward,
            // braking tilts it forward, strafing left tilts it right, and
            // strafing right tilts it left. The camera-derived terms (yaw turn
            // on the X channel, pitch on the Z channel) keep their own sign
            // because they track the camera, not the movement.
            //
            // The tilt ramps continuously out of a small deadband, so
            // constant-speed movement stays still and threshold-level jitter
            // cannot flicker the bone.
            double strafeTilt = -movementTilt(strafeAccel, max);
            double forwardTilt = -movementTilt(forwardAccel, max);
            double turnTilt = clamp(snapshot.lateralTurn() * settings.turnGain(), max);
            Rotation target = new Rotation(strafeTilt + turnTilt, 0.0,
                forwardTilt + cameraPitchDegrees);
            Map<String, InheritedRotation> evaluated = new LinkedHashMap<>();
            Set<String> visiting = new HashSet<>();
            for (String boneId : boneIds) updateBone(boneId, boneIds, parentIds, localFrames,
                target, dt, evaluated, visiting);
        } finally {
            lock.unlock();
        }
    }

    private InheritedRotation updateBone(String id, Set<String> boneIds,
                                         Map<String, String> parentIds,
                                         Map<String, Quaterniond> localFrames, Rotation target,
                                         double dt, Map<String, InheritedRotation> evaluated,
                                         Set<String> visiting) {
        InheritedRotation cached = evaluated.get(id);
        if (cached != null) return cached;
        if (!visiting.add(id)) throw new IllegalArgumentException("Cyclic physics hierarchy at " + id);
        String parent = parentIds.get(id);
        InheritedRotation inherited = parent == null
            ? new InheritedRotation(new Quaterniond(), false)
            : updateBone(parent, boneIds, parentIds, localFrames, target, dt, evaluated, visiting);
        Quaterniond frame = localFrames.getOrDefault(id, new Quaterniond());
        Quaterniond cumulative = new Quaterniond(frame).invert()
            .mul(inherited.rotation()).mul(frame).normalize();
        boolean physics = boneIds.contains(id);
        if (physics) {
            Swing swing = bones.computeIfAbsent(id,
                ignored -> new Swing(settings.frequency(), settings.damping()));
            if (inherited.physics()) {
                Quaterniond residual = new Quaterniond(cumulative).invert()
                    .mul(target.quaternion()).normalize();
                swing.updateRelative(residual, dt);
            } else {
                swing.update(target.pitchDegrees(), target.rollDegrees(), dt);
            }
            cumulative.mul(swing.rotation().quaternion()).normalize();
        }
        InheritedRotation result = new InheritedRotation(cumulative, physics || inherited.physics());
        evaluated.put(id, result);
        visiting.remove(id);
        return result;
    }

    private record InheritedRotation(Quaterniond rotation, boolean physics) {}

    /**
     * Current local-space rotation of each tracked bone in degrees, relative
     * to the animated orientation. The values are the settled spring states
     * on the bone's fixed local axes: the pitch channel rotates about the
     * bone-local X axis (Blockbench X, sign-flipped by the parser) and the
     * roll channel about the bone-local Z axis (Blockbench Z, sign-flipped);
     * the yaw channel is zero for isolated bones. Chained bones instead report
     * the relative quaternion (Euler values are diagnostic only). The delta
     * is handed out as-is
     * — never conjugated through the bone's current world rotation — the
     * applied rotation axes are identical whatever the player's world
     * rotation, and a zero tilt yields exactly the identity so the animation
     * plays untouched.
     */
    public Map<String, Rotation> rotations(Set<String> boneIds) {
        if (boneIds == null || boneIds.isEmpty()) return Map.of();
        lock.lock();
        try {
            Map<String, Rotation> result = new LinkedHashMap<>();
            for (String boneId : boneIds) {
                Swing swing = bones.get(boneId);
                if (swing == null) continue;
                result.put(boneId, swing.rotation());
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    void reset() {
        lock.lock();
        try {
            resetLocked();
        } finally {
            lock.unlock();
        }
    }

    private void resetLocked() {
        bones.clear();
        lastSnapshot = null;
    }

    /**
     * Continuous-deadband movement tilt: acceleration within the threshold
     * band produces exactly zero tilt, and beyond it the tilt ramps linearly
     * from zero ({@code (|a| − threshold) · gain}) so the response is smooth
     * at the band edge and per-tick velocity jitter cannot flicker the bone.
     */
    private double movementTilt(double accel, double max) {
        double magnitude = Math.abs(accel) - ACCEL_THRESHOLD;
        if (magnitude <= 0.0) return 0.0;
        return clamp(Math.signum(accel) * magnitude * settings.accelerationGain()
            * DEGREES_PER_UNIT, max);
    }

    private static boolean isFinite(NativeMotionSampler.PhysicsSnapshot snapshot) {
        return snapshot != null
            && Double.isFinite(snapshot.dtSeconds())
            && Double.isFinite(snapshot.forwardAccelX())
            && Double.isFinite(snapshot.forwardAccelZ())
            && Double.isFinite(snapshot.velocityX())
            && Double.isFinite(snapshot.velocityZ())
            && Double.isFinite(snapshot.lateralTurn())
            && Double.isFinite(snapshot.yawRadians())
            && Double.isFinite(snapshot.pitchRadians());
    }

    private static double clamp(double value, double limit) {
        return Math.max(-limit, Math.min(limit, value));
    }

    /** Local delta around the pivot; chained Euler values are diagnostic only. */
    public record Rotation(double pitchDegrees, double yawDegrees, double rollDegrees, Quaterniond quaternion) {
        Rotation(double pitchDegrees, double yawDegrees, double rollDegrees) {
            this(pitchDegrees, yawDegrees, rollDegrees, new Quaterniond()
                .rotationZYX(0.0, 0.0, Math.toRadians(pitchDegrees))
                .mul(new Quaterniond().rotationZYX(Math.toRadians(rollDegrees),
                    Math.toRadians(yawDegrees), 0.0)));
        }

        static Rotation relative(Quaterniond quaternion) {
            Vector3d euler = quaternion.getEulerAnglesXYZ(new Vector3d());
            return new Rotation(Math.toDegrees(euler.x), Math.toDegrees(euler.y),
                Math.toDegrees(euler.z), quaternion);
        }

        @Override
        public Quaterniond quaternion() { return new Quaterniond(quaternion); }

        static final Rotation ZERO = new Rotation(0.0, 0.0, 0.0);
    }

    /**
     * Isolated bones retain the existing X/Z springs. Descendants spring
     * toward the remaining local rotation as a quaternion rotation vector.
     */
    private static final class Swing {
        private final DampedSpring pitch;
        private final DampedSpring roll;
        private final DampedSpring relativeX;
        private final DampedSpring relativeY;
        private final DampedSpring relativeZ;
        private final Vector3d relativeTarget = new Vector3d();
        private boolean relative;

        Swing(double frequency, double damping) {
            this.pitch = new DampedSpring(frequency, damping);
            this.roll = new DampedSpring(frequency, damping);
            this.relativeX = new DampedSpring(frequency, damping);
            this.relativeY = new DampedSpring(frequency, damping);
            this.relativeZ = new DampedSpring(frequency, damping);
        }

        public void update(double pitchTilt, double rollTilt, double dt) {
            relative = false;
            pitch.update(pitchTilt, dt);
            roll.update(rollTilt, dt);
        }

        void updateRelative(Quaterniond target, double dt) {
            relative = true;
            // Start on the shortest arc, then choose the equivalent rotation
            // vector closest to the previous target. Otherwise a parent's tiny
            // overshoot around a half-turn flips +180 to -180 every oscillation.
            double sign = target.w < 0.0 ? -1.0 : 1.0;
            double length = Math.sqrt(target.x * target.x + target.y * target.y + target.z * target.z);
            double turn = 2.0 * Math.PI;
            if (length < 1.0e-12) {
                double previousLength = relativeTarget.length();
                if (previousLength > Math.PI) {
                    relativeTarget.mul(turn * Math.rint(previousLength / turn) / previousLength);
                } else {
                    relativeTarget.set(target.x * 2.0 * sign, target.y * 2.0 * sign,
                        target.z * 2.0 * sign);
                }
            } else {
                Vector3d axis = new Vector3d(target.x, target.y, target.z).mul(sign / length);
                double angle = 2.0 * Math.atan2(length, Math.abs(target.w));
                double winding = Math.rint((relativeTarget.dot(axis) - angle) / turn);
                relativeTarget.set(axis).mul(angle + winding * turn);
            }
            relativeX.update(relativeTarget.x, dt);
            relativeY.update(relativeTarget.y, dt);
            relativeZ.update(relativeTarget.z, dt);
        }

        Rotation rotation() {
            if (relative) {
                double x = relativeX.value(), y = relativeY.value(), z = relativeZ.value();
                double angle = Math.sqrt(x * x + y * y + z * z);
                double factor = angle < 1.0e-12 ? 0.5 : Math.sin(angle * 0.5) / angle;
                return Rotation.relative(new Quaterniond(x * factor, y * factor, z * factor,
                    Math.cos(angle * 0.5)));
            }
            return new Rotation(pitch.value(), 0.0, roll.value());
        }
    }
}
