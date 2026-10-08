package com.armaturemc.client;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClientViewTransformTest {
    @Test void handPassMatchesServerShaderProjectionAndMountedOriginAcrossFovs() {
        Vector3f vertex = new Vector3f(.65f, -.4f, -1.2f);
        for (float targetFov : new float[] {70, 90, 110, 135}) {
            Matrix4f worldProjection = new Matrix4f().perspective((float)Math.toRadians(targetFov), 16f / 9, .05f, 1000);
            Matrix4f handProjection = new Matrix4f().perspective((float)Math.toRadians(70), 16f / 9, .05f, 100);
            Vector3f server = worldProjection.transformProject(new Vector3f(vertex).add(0, .19f, 0));
            Vector3f client = handProjection.mul(ClientViewTransform.matrix(70, targetFov, .19f))
                .transformProject(new Vector3f(vertex));
            assertEquals(server.x, client.x, 1e-6, "horizontal framing at FOV " + targetFov);
            assertEquals(server.y, client.y, 1e-6, "vertical framing at FOV " + targetFov);
        }
    }

    @Test void legacyAndModernOriginsStayAtTheSameViewportYAcrossCameraMovement() {
        for (float origin : new float[]{0, .19f}) {
            var attached = ClientViewTransform.matrix(70, 70, origin);
            var vertex = new Vector3f(.65f, -.4f, -1.2f);
            var expected = attached.transformPosition(new Vector3f(vertex));
            for (int frame = 0; frame < 240; frame++) {
                // Camera rotation plus an alternate render-pass translation. The
                // hand's inherited bob/hurt stack is deliberately not reused.
                var view = new Matrix4f().rotateX((float)Math.sin(frame / 17.0))
                    .rotateY(frame / 23f).rotateZ(frame / 97f).translate(frame / 10f, frame / 7f, -3);
                var actual = view.mul(ClientViewTransform.viewport(view)).mul(attached)
                    .transformPosition(new Vector3f(vertex));
                assertEquals(expected.x, actual.x, 1e-5);
                assertEquals(expected.y, actual.y, 1e-5);
                assertEquals(expected.z, actual.z, 1e-5);
            }
        }
    }
}
