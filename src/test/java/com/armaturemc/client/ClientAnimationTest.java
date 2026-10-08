package com.armaturemc.client;

import com.armaturemc.client.protocol.ClientProtocol;
import com.armaturemc.renderer.internal.animation.MolangContext;
import com.google.gson.JsonParser;
import java.util.*;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClientAnimationTest {
    @Test void unkeyedBonesAndChannelsReturnToRestDuringAndAfterAClipReplacement() {
        for (String emptyAnimator : new String[]{"", ",\"orphan\":{\"type\":\"bone\",\"keyframes\":[]}"}) {
            var doc = JsonParser.parseString("""
                {"groups":[{"uuid":"mover","name":"mover","origin":[0,0,0]},
                           {"uuid":"orphan","name":"orphan","origin":[0,0,0]}],
                 "elements":[],"outliner":[{"uuid":"mover","children":[]},{"uuid":"orphan","children":[]}],
                 "animations":[
                   {"name":"old","length":1,"loop":"hold","animators":{
                     "orphan":{"type":"bone","keyframes":[
                       {"channel":"position","time":0,"data_points":[{"x":16,"y":0,"z":0}]},
                       {"channel":"rotation","time":0,"data_points":[{"x":90,"y":0,"z":0}]},
                       {"channel":"scale","time":0,"data_points":[{"x":2,"y":3,"z":4}]}
                     ]}}},
                   {"name":"next","length":1,"loop":"hold","animators":{
                     "mover":{"type":"bone","keyframes":[
                       {"channel":"position","time":0,"data_points":[{"x":8,"y":0,"z":0}]}
                     ]}%s}}]}
                """.formatted(emptyAnimator)).getAsJsonObject();
            for (String layer : new String[]{"loop", "action"}) {
                var animation = new ClientAnimation(doc);
                var bones = List.of(new ClientProtocol.Bone("mover", true), new ClientProtocol.Bone("orphan", true));
                var session = UUID.randomUUID(); long start = 1_000_000_000L;
                animation.receive(new ClientProtocol.Frame(0, session, "a".repeat(64), 1, true,
                    new int[0], bones, ClientProtocol.View.defaults(),
                    "{\"" + layer + "\":{\"name\":\"old\",\"epoch\":1,\"elapsed\":0}}"), start, MolangContext.defaults());
                var old = animation.matrices(start, MolangContext.defaults(), bones).get("orphan");
                assertEquals(1, old.m30(), 1e-6);
                animation.receive(new ClientProtocol.Frame(0, session, "a".repeat(64), 2, true,
                    new int[0], bones, ClientProtocol.View.defaults(),
                    "{\"" + layer + "\":{\"name\":\"next\",\"epoch\":2,\"elapsed\":0,\"crossfade\":true},"
                        + "\"transition\":42,\"transitionRemaining\":0.2}"), start + 100_000_000L, MolangContext.defaults());
                var halfway = animation.matrices(start + 200_000_000L, MolangContext.defaults(), bones).get("orphan");
                assertEquals(.5, halfway.m30(), 1e-6, "unkeyed bone must fade toward zero, not retain its old transform");
                assertTrue(new Matrix4f().translation(.5f, 0, 0).rotateX((float)Math.PI / 4)
                    .scale(1.5f, 2, 2.5f).equals(halfway, 1e-5f));
                for (long now : new long[]{start + 300_000_000L, start + 500_000_000L}) {
                    var matrices = animation.matrices(now, MolangContext.defaults(), bones);
                    assertTrue(new Matrix4f().equals(matrices.get("orphan"), 1e-6f));
                    assertEquals(.5, matrices.get("mover").m30(), 1e-6);
                }
            }
        }
    }

    @Test void firstEquipCannotFadeBackToTheRestPoseWhenTheNextServerSnapshotArrives() {
        var doc = JsonParser.parseString(document().replace("\"x\":\"0\"", "\"x\":\"16\"")
            .replace("\"x\":\"16\"", "\"x\":\"32\"")).getAsJsonObject();
        // A model held at x=2 is in view; fading from its unanimated rest would hide its intro.
        var animation = new ClientAnimation(doc);
        var bone = new ClientProtocol.Bone("bone", true); var session = UUID.randomUUID();
        long start = 1_000_000_000L;
        animation.receive(new ClientProtocol.Frame(0, session, "a".repeat(64), 1, true,
            new int[0], List.of(bone), ClientProtocol.View.defaults(),
            "{\"action\":{\"name\":\"move\",\"epoch\":1,\"elapsed\":0,\"in\":10,\"crossfade\":true}}"), start, MolangContext.defaults());
        assertEquals(2, animation.matrices(start, MolangContext.defaults(), List.of(bone)).get("bone").m30(), 1e-6);
        for (int i = 1; i <= 8; i++) {
            long now = start + i * 50_000_000L;
            animation.receive(new ClientProtocol.Frame(0, session, "a".repeat(64), i + 1, true,
                new int[0], List.of(bone), ClientProtocol.View.defaults(),
                "{\"action\":{\"name\":\"move\",\"epoch\":1,\"elapsed\":0,\"in\":10,\"crossfade\":false}}"), now, MolangContext.defaults());
            assertEquals(2, animation.matrices(now, MolangContext.defaults(), List.of(bone)).get("bone").m30(), 1e-6);
        }
    }
    @Test void serverReprojectionZoomSettingsCannotChangePlayerFovInClientCameraMode() {
        var doc = JsonParser.parseString(document().replace("\"position\"", "\"rotation\"")).getAsJsonObject();
        doc.getAsJsonArray("groups").get(0).getAsJsonObject().addProperty("clientCamera", true);
        for (String mode : new String[]{"fixed", "dynamic"}) for (boolean preserve : new boolean[]{false, true}) {
            var animation = new ClientAnimation(doc);
            animation.configure(JsonParser.parseString("{\"camera\":{\"enabled\":true,\"zoomEnabled\":true,\"zoomMode\":\""
                + mode + "\",\"maximumZoom\":1.35,\"preserveFov\":" + preserve + "}}").getAsJsonObject());
            var bone = new ClientProtocol.Bone("bone", true);
            long now = 1_000_000_000L;
            animation.receive(frame(UUID.randomUUID(), 1, .5, bone), now, MolangContext.defaults());
            var effect = animation.cameraEffect(now, MolangContext.defaults());
            assertTrue(effect.rotates());
            var projection = new Matrix4f().perspective((float)Math.toRadians(110), 16f / 9, .05f, 512);
            assertEquals(projection, effect.projection(projection));
            assertEquals(1, effect.handTransform().getScale(new org.joml.Vector3f()).x, 1e-6);
        }
    }
    @Test void zeroScaleFirstFrameCanGrowWithoutAnotherServerPose() {
        var doc = JsonParser.parseString(document().replace("\"position\"", "\"scale\"")
            .replace("\"x\":\"16\",\"y\":\"0\",\"z\":\"0\"", "\"x\":\"1\",\"y\":\"1\",\"z\":\"1\""))
            .getAsJsonObject();
        var animation = new ClientAnimation(doc);
        var bone = new ClientProtocol.Bone("bone", true);
        animation.receive(new ClientProtocol.Frame(0, UUID.randomUUID(), "a".repeat(64), 1, true,
            new int[0], List.of(bone), ClientProtocol.View.defaults(), "{\"loop\":{\"name\":\"move\",\"elapsed\":0}}"),
            1_000_000_000L, MolangContext.defaults());
        var matrix = animation.matrices(1_250_000_000L, MolangContext.defaults(), List.of(bone)).get("bone");
        assertEquals(.25, matrix.m00(), 1e-6);
        assertEquals(.25, matrix.m11(), 1e-6);
        assertEquals(.25, matrix.m22(), 1e-6);
        assertTrue(ClientModel.drawable(matrix));
    }

    @Test void nonuniformParentScaleTransformsChildPivotAndItsAnimatedScale() {
        var doc = JsonParser.parseString("""
            {"groups":[{"uuid":"parent","name":"parent","origin":[0,0,0]},
                        {"uuid":"child","name":"child","origin":[16,16,0]}],
             "elements":[],"outliner":[{"uuid":"parent","children":[{"uuid":"child","children":[]}]}],
             "animations":[{"name":"scale","length":1,"loop":"hold","animators":{
               "parent":{"type":"bone","keyframes":[{"channel":"scale","time":0,"data_points":[{"x":2,"y":0.5,"z":-1}]}]},
               "child":{"type":"bone","keyframes":[{"channel":"scale","time":0,"data_points":[{"x":0.25,"y":3,"z":1}]}]}}}]}
            """).getAsJsonObject();
        var animation = new ClientAnimation(doc);
        var bones = List.of(new ClientProtocol.Bone("parent", true), new ClientProtocol.Bone("child", true));
        long start = 1_000_000_000L;
        animation.receive(new ClientProtocol.Frame(0, UUID.randomUUID(), "a".repeat(64), 1, true,
            new int[0], bones, ClientProtocol.View.defaults(), "{\"loop\":{\"name\":\"scale\",\"elapsed\":0}}"),
            start, MolangContext.defaults());
        var matrix = animation.matrices(start, MolangContext.defaults(), bones).get("child");
        assertTrue(new Matrix4f().scaling(2, .5f, -1).translate(-1, 1, 0).scale(.25f, 3, 1).equals(matrix, 1e-6f));
        assertTrue(ClientModel.drawable(matrix));
    }
    @Test void unchangedClipEpochKeepsLocalClockAcrossDelayedServerSnapshots() {
        var animation = new ClientAnimation(JsonParser.parseString(document()).getAsJsonObject());
        var bone = new ClientProtocol.Bone("bone", true);
        UUID session = UUID.randomUUID();
        animation.receive(new ClientProtocol.Frame(0, session, "a".repeat(64), 1, true,
            new int[0], List.of(bone), ClientProtocol.View.defaults(),
            "{\"loop\":{\"name\":\"move\",\"epoch\":1,\"elapsed\":0}}"), 1_000_000_000L, MolangContext.defaults());
        var delayed = new ClientProtocol.Bone("bone", true);
        animation.receive(new ClientProtocol.Frame(0, session, "a".repeat(64), 2, true,
            new int[0], List.of(delayed), ClientProtocol.View.defaults(),
            "{\"loop\":{\"name\":\"move\",\"epoch\":1,\"elapsed\":0.05}}"), 1_090_000_000L, MolangContext.defaults());
        assertEquals(.1, animation.matrices(1_100_000_000L, MolangContext.defaults(), List.of(delayed)).get("bone").m30(), 1e-6);
    }
    @Test void oneStateMessageDrives144DistinctAuthoredSamplesPerSecond() {
        var animation = new ClientAnimation(JsonParser.parseString(document()).getAsJsonObject());
        long start = 1_000_000_000L;
        float[] reference = new Matrix4f().translation(.45f, -.35f, -.8f).rotateY((float)Math.PI).get(new float[16]);
        var options = new com.google.gson.JsonObject();
        var basis = new com.google.gson.JsonArray(); for (float value : reference) basis.add(value);
        options.add("basis", basis); animation.configure(options);
        var bone = new ClientProtocol.Bone("bone", true);
        var frame = new ClientProtocol.Frame(0, UUID.randomUUID(), "a".repeat(64), 1, true,
            new int[]{123}, List.of(bone), ClientProtocol.View.defaults(),
            "{\"loop\":{\"name\":\"move\",\"elapsed\":0,\"speed\":1,\"in\":0,\"out\":0}}");
        animation.receive(frame, start, MolangContext.defaults());
        Set<Float> samples = new HashSet<>();
        for (int i = 0; i < 144; i++) {
            double seconds = i / 144.0;
            float x = animation.matrices(start + (long)(seconds * 1e9), MolangContext.defaults(), List.of(bone)).get("bone").m30();
            assertEquals(.45 - seconds, x, 1e-5);
            samples.add(x);
        }
        assertEquals(144, samples.size());
    }

    @Test void actionUsesSparsePriorityAndReversePlaybackWithoutChangingReferencePose() {
        var animation = new ClientAnimation(JsonParser.parseString(document()).getAsJsonObject());
        var bone = new ClientProtocol.Bone("bone", true);
        var frame = new ClientProtocol.Frame(0, UUID.randomUUID(), "a".repeat(64), 1, true,
            new int[0], List.of(bone), ClientProtocol.View.defaults(),
            "{\"action\":{\"name\":\"move\",\"elapsed\":0,\"speed\":-1,\"in\":0,\"out\":0}}");
        animation.receive(frame, 1_000_000_000L, MolangContext.defaults());
        assertEquals(1, animation.matrices(1_000_000_000L, MolangContext.defaults(), List.of(bone)).get("bone").m30(), 1e-6);
        assertEquals(.75, animation.matrices(1_250_000_000L, MolangContext.defaults(), List.of(bone)).get("bone").m30(), 1e-6);
    }

    @Test void delayedTwentyHzMessagesDoNotRebaseNonlinearMolangRotationsAtAnyRefreshRate() {
        var doc = JsonParser.parseString(document().replace("\"position\"", "\"rotation\"")
            .replace("\"x\":\"0\"", "\"x\":\"math.sin(query.anim_time * 360) * 45 + query.head_y_rotation\"")
            .replace("\"x\":\"16\"", "\"x\":\"math.sin(query.anim_time * 360) * 45 + query.head_y_rotation\""))
            .getAsJsonObject();
        for (int fps : new int[]{30, 60, 144, 240}) {
            var streamed = new ClientAnimation(doc); var uninterrupted = new ClientAnimation(doc);
            var bone = new ClientProtocol.Bone("bone", true); UUID session = UUID.randomUUID();
            long start = 1_000_000_000L; int sequence = 1;
            streamed.receive(frame(session, sequence, 0, bone), start, MolangContext.defaults());
            uninterrupted.receive(frame(session, sequence, 0, bone), start, MolangContext.defaults());
            double nextPacket = .075;
            for (int i = 1; i < fps * 2; i++) {
                double seconds = i / (double)fps; long now = start + Math.round(seconds * 1e9);
                var viewer = MolangContext.defaults().withViewer(seconds * 40, seconds * 10);
                Matrix4f before = streamed.matrices(now, viewer, List.of(bone)).get("bone");
                if (seconds >= nextPacket) {
                    // The packet's server clock is stale; its receive-time Molang context differs too.
                    streamed.receive(frame(session, ++sequence, Math.max(0, seconds - .04), bone), now,
                        MolangContext.defaults().withViewer(-70, 80));
                    nextPacket += .05 + (sequence % 2 == 0 ? .012 : -.012);
                    assertTrue(before.equals(streamed.matrices(now, viewer, List.of(bone)).get("bone"), 1e-5f),
                        "Packet must not move the pose at " + fps + " FPS, packet " + sequence);
                }
                assertTrue(uninterrupted.matrices(now, viewer, List.of(bone)).get("bone").equals(
                    streamed.matrices(now, viewer, List.of(bone)).get("bone"), 1e-5f));
            }
        }
    }

    @Test void localOffsetQuaternionComposesAfterAuthoredRotationAndStaysStableAcrossPackets() {
        var doc = JsonParser.parseString(document().replace("\"position\"", "\"rotation\"")
            .replace("\"16\"", "\"90\"" )).getAsJsonObject();
        var animation = new ClientAnimation(doc);
        var q = new org.joml.Quaternionf().rotationY(.5f);
        var bone = new ClientProtocol.Bone("bone", true, new float[]{.2f, -.3f, .1f, q.x, q.y, q.z, q.w});
        UUID session = UUID.randomUUID(); long start = 1_000_000_000L;
        animation.receive(frame(session, 1, 0, bone), start, MolangContext.defaults());
        var expected = new Matrix4f().translation(.2f, -.3f, .1f).rotateX((float)Math.PI / 4).rotate(q);
        var actual = animation.matrices(start + 500_000_000L, MolangContext.defaults(), List.of(bone)).get("bone");
        assertTrue(expected.equals(actual, 1e-5f));
        animation.receive(frame(session, 2, .2, bone), start + 500_000_000L, MolangContext.defaults());
        assertTrue(actual.equals(animation.matrices(start + 500_000_000L, MolangContext.defaults(), List.of(bone)).get("bone"), 1e-5f));
    }

    @Test void changingServerAdditiveIsInterpolatedWithoutMovingTheAnimationClock() {
        var animation = new ClientAnimation(JsonParser.parseString(document()).getAsJsonObject());
        UUID session = UUID.randomUUID(); var rest = new ClientProtocol.Bone("bone", true);
        long start = 1_000_000_000L;
        animation.receive(frame(session, 1, 0, rest), start, MolangContext.defaults());
        var moved = new ClientProtocol.Bone("bone", true, new float[]{0, 1, 0, 0, 0, 0, 1});
        animation.receive(frame(session, 2, .01, moved), start + 100_000_000L, MolangContext.defaults());
        var pose = animation.matrices(start + 125_000_000L, MolangContext.defaults(), List.of(moved)).get("bone");
        assertEquals(.125, pose.m30(), 1e-6); assertEquals(.5, pose.m31(), 1e-6);
    }

    @Test void serverTransitionCompletionCannotCutTheLocalFadeShort() {
        var doc = JsonParser.parseString(document()).getAsJsonObject();
        doc.getAsJsonArray("animations").get(0).getAsJsonObject().addProperty("loop", "hold");
        var animation = new ClientAnimation(doc); var bone = new ClientProtocol.Bone("bone", true);
        UUID session = UUID.randomUUID(); long start = 1_000_000_000L;
        animation.receive(frame(session, 1, 0, bone), start, MolangContext.defaults());
        animation.receive(new ClientProtocol.Frame(0, session, "a".repeat(64), 2, true, new int[0], List.of(bone),
            ClientProtocol.View.defaults(), "{\"loop\":{\"name\":\"move\",\"epoch\":2,\"elapsed\":0.5},\"transition\":42,\"transitionRemaining\":0.2}"),
            start + 100_000_000L, MolangContext.defaults());
        long now = start + 250_000_000L;
        var before = animation.matrices(now, MolangContext.defaults(), List.of(bone)).get("bone");
        animation.receive(new ClientProtocol.Frame(0, session, "a".repeat(64), 3, true, new int[0], List.of(bone),
            ClientProtocol.View.defaults(), "{\"loop\":{\"name\":\"move\",\"epoch\":2,\"elapsed\":0.65}}"), now, MolangContext.defaults());
        assertTrue(before.equals(animation.matrices(now, MolangContext.defaults(), List.of(bone)).get("bone"), 1e-6f));
        assertEquals(.7, animation.matrices(start + 300_000_000L, MolangContext.defaults(), List.of(bone)).get("bone").m30(), 1e-6);
    }

    @Test void cameraAndModelReuseOneMolangEvaluationInTheSameRenderFrame() {
        var doc = JsonParser.parseString(document().replace("\"position\"", "\"rotation\"")
            .replace("\"x\":\"0\"", "\"x\":\"math.random(0, 45)\"")
            .replace("\"x\":\"16\"", "\"x\":\"math.random(0, 45)\"" )).getAsJsonObject();
        doc.getAsJsonArray("groups").get(0).getAsJsonObject().addProperty("clientCamera", true);
        var animation = new ClientAnimation(doc);
        animation.configure(JsonParser.parseString("{\"camera\":{\"enabled\":true}}").getAsJsonObject());
        var draws = new java.util.concurrent.atomic.AtomicInteger();
        var viewer = MolangContext.defaults().withRandom(() -> { draws.incrementAndGet(); return .5; });
        var bone = new ClientProtocol.Bone("bone", true); long start = 1_000_000_000L;
        animation.receive(frame(UUID.randomUUID(), 1, 0, bone), start, viewer);
        var model = animation.matrices(start + 250_000_000L, viewer, List.of(bone)).get("bone");
        int sampled = draws.get(); assertTrue(sampled > 0);
        var camera = animation.cameraEffect(start + 250_000_000L, viewer).handTransform();
        assertEquals(sampled, draws.get(), "Projection calls must not independently resample Molang");
        assertTrue(model.equals(camera, 1e-6f));
    }

    @Test void incomingTransitionStartsFromDisplayedPoseInsteadOfPacketTimeViewerInput() {
        var doc = JsonParser.parseString(document().replace("\"position\"", "\"rotation\"")
            .replace("\"x\":\"0\"", "\"x\":\"query.head_y_rotation\"")
            .replace("\"x\":\"16\"", "\"x\":\"query.head_y_rotation\"" )).getAsJsonObject();
        var animation = new ClientAnimation(doc); var bone = new ClientProtocol.Bone("bone", true);
        UUID session = UUID.randomUUID(); long start = 1_000_000_000L;
        animation.receive(frame(session, 1, 0, bone), start, MolangContext.defaults());
        var displayed = animation.matrices(start + 100_000_000L,
            MolangContext.defaults().withViewer(35, 0), List.of(bone)).get("bone");
        long receiveAt = start + 110_000_000L;
        animation.receive(new ClientProtocol.Frame(0, session, "a".repeat(64), 2, true, new int[0], List.of(bone),
            ClientProtocol.View.defaults(), "{\"loop\":{\"name\":\"move\",\"epoch\":2,\"elapsed\":0},\"transition\":123,\"transitionRemaining\":0.2}"),
            receiveAt, MolangContext.defaults().withViewer(-70, 0));
        assertTrue(displayed.equals(animation.matrices(receiveAt,
            MolangContext.defaults().withViewer(-70, 0), List.of(bone)).get("bone"), 1e-6f));
    }

    private static ClientProtocol.Frame frame(UUID session, int sequence, double elapsed, ClientProtocol.Bone bone) {
        return new ClientProtocol.Frame(0, session, "a".repeat(64), sequence, true, new int[0], List.of(bone),
            ClientProtocol.View.defaults(), "{\"loop\":{\"name\":\"move\",\"epoch\":1,\"elapsed\":" + elapsed + "}}");
    }

    static String document() {
        return """
          {"name":"test","groups":[{"uuid":"bone","name":"bone","origin":[0,0,0],"rotation":[0,0,0]}],
           "elements":[],"outliner":[{"uuid":"bone","children":[]}],"animations":[
            {"name":"move","length":1,"loop":"loop","animators":{"bone":{"name":"bone","type":"bone","keyframes":[
              {"channel":"position","time":0,"interpolation":"linear","data_points":[{"x":"0","y":"0","z":"0"}]},
              {"channel":"position","time":1,"interpolation":"linear","data_points":[{"x":"16","y":"0","z":"0"}]}]}}}]}
          """;
    }
}
