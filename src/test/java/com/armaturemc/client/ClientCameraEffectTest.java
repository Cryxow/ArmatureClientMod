package com.armaturemc.client;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClientCameraEffectTest {
    @Test void actualCameraViewMatchesTheFormerProjectionRotationAtEveryPlayerOrientation() {
        var viewEffect = new Quaternionf().rotationZYX(.08f, -.05f, .1f);
        var effect = new ClientCameraEffect(viewEffect);
        for (float yaw : new float[]{-179, -90, 0, 90, 179}) {
            for (float pitch : new float[]{-89, -45, 0, 45, 89}) {
                var vanilla = new Quaternionf().rotationYXZ((float)(Math.PI - Math.toRadians(yaw)),
                    (float)-Math.toRadians(pitch), 0);
                var oldView = new Matrix4f().rotate(viewEffect).rotate(new Quaternionf(vanilla).conjugate());
                var renderedView = new Matrix4f().rotate(effect.orientation(vanilla).conjugate());
                assertTrue(oldView.equals(renderedView, 1e-5f), "yaw=" + yaw + " pitch=" + pitch);
                var projection = new Matrix4f().perspective((float)Math.toRadians(90), 16f / 9, .05f, 512);
                var oldClip = new Matrix4f(projection).mul(effect.handTransform()).rotate(new Quaternionf(vanilla).conjugate());
                var newClip = effect.projection(projection).mul(renderedView);
                assertTrue(oldClip.equals(newClip, 1e-5f));
                var orientation = effect.orientation(vanilla);
                var forward = new Vector3f(0, 0, -1).rotate(orientation);
                var up = new Vector3f(0, 1, 0).rotate(orientation);
                var left = new Vector3f(-1, 0, 0).rotate(orientation);
                assertEquals(0, forward.dot(up), 1e-5); assertEquals(0, forward.dot(left), 1e-5);
                var eulerForward = new Vector3f(0, 0, -1).rotate(new Quaternionf().rotationYXZ(
                    (float)(Math.PI - Math.toRadians(ClientCameraEffect.yaw(forward, yaw))),
                    (float)-Math.toRadians(ClientCameraEffect.pitch(forward)), 0));
                assertTrue(forward.equals(eulerForward, 1e-5f));
            }
        }
    }

    @Test void cameraRotationPreservesEveryPlayerFovAndAllPerspectiveCoefficients() {
        var effect = new ClientCameraEffect(new Quaternionf().rotationZYX(.1f, .07f, -.04f));
        for (float fov : new float[]{30, 70, 90, 110}) {
            var vanilla = new Matrix4f().perspective((float)Math.toRadians(fov), 16f / 9, .05f, 1024);
            var result = effect.projection(vanilla);
            assertNotSame(vanilla, result); assertEquals(vanilla, result);
            assertEquals(fov, Math.toDegrees(2 * Math.atan(1 / result.m11())), 1e-5);
            assertEquals(1, effect.handTransform().getScale(new Vector3f()).x, 1e-6);
            result.scale(2); // The returned copy cannot alter Minecraft's projection.
            assertTrue(new Matrix4f().perspective((float)Math.toRadians(fov), 16f / 9, .05f, 1024).equals(vanilla));
        }
    }

    @Test void effectsDoNotAccumulateOrMutateStoredRotationAcrossFramesOrHandPasses() {
        var input = new Quaternionf().rotationX(.1f);
        var effect = new ClientCameraEffect(input);
        input.identity();
        assertTrue(effect.rotates()); effect.viewRotation().identity(); assertTrue(effect.rotates());
        var vanilla = new Quaternionf().rotationYXZ(1, .2f, 0); var saved = new Quaternionf(vanilla);
        assertTrue(effect.orientation(vanilla).equals(effect.orientation(vanilla), 1e-6f));
        assertEquals(saved, vanilla);
        assertTrue(effect.handTransform().equals(effect.handTransform()));
        assertFalse(ClientCameraEffect.IDENTITY.rotates());
        assertTrue(saved.equals(ClientCameraEffect.IDENTITY.orientation(saved), 1e-6f));
    }

    @Test void shaderpackPerspectiveShortcutMatchesFullProjectionWithRotationInView() {
        // Photon 1.3b's project() assumes a perspective matrix and omits its off-diagonal rotation terms.
        var effect = new ClientCameraEffect(new Quaternionf().rotationZYX(.06f, .1f, -.08f));
        var projection = new Matrix4f().perspective((float)Math.toRadians(80), 16f / 9, .05f, 1024);
        var camera = new Quaternionf().rotationYXZ(.3f, -.1f, 0);
        var vanillaView = new Matrix4f().rotate(new Quaternionf(camera).conjugate());
        var correctedView = new Matrix4f().rotate(effect.orientation(camera).conjugate());
        for (float distance : new float[]{4, 128, 10000}) {
            var worldPoint = new Vector3f(.4f, .3f, -distance);
            var viewPoint = correctedView.transformPosition(new Vector3f(worldPoint));
            var expected = effect.projection(projection).transformProject(new Vector3f(viewPoint));
            assertTrue(expected.equals(shaderpackProject(effect.projection(projection), viewPoint), 1e-6f));
        }
        var oldProjection = new Matrix4f(projection).mul(effect.handTransform());
        var oldViewPoint = vanillaView.transformPosition(new Vector3f(.4f, .3f, -4));
        var actualFullRotation = oldProjection.transformProject(new Vector3f(oldViewPoint));
        assertTrue(actualFullRotation.distance(shaderpackProject(oldProjection, oldViewPoint)) > .01,
            "The former rotated projection must reproduce the shaderpack disagreement");
    }

    private static Vector3f shaderpackProject(Matrix4f matrix, Vector3f view) {
        float w = matrix.m23() * view.z + matrix.m33();
        return new Vector3f(matrix.m00() * view.x + matrix.m30(), matrix.m11() * view.y + matrix.m31(),
            matrix.m22() * view.z + matrix.m32()).div(w);
    }
}
