package com.armaturemc.client;

import com.armaturemc.client.protocol.ClientProtocol;
import com.armaturemc.renderer.internal.animation.MolangContext;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClientModelSessionsTest {
    private static final String HASH = "a".repeat(64);
    private static final class Model {
        int pose;
        Model(int pose) { this.pose = pose; }
    }

    @Test void clearThenOutgoingOfferRetainsTheExactSessionAndDisplayedPose() {
        var disposed = new ArrayList<Model>();
        var sessions = new ClientModelSessions<Model>(8, 100, ignored -> new Model(0), ignored -> 10, disposed::add);
        UUID id = UUID.randomUUID(); var model = new Model(37);
        assertNull(sessions.offer(0, id, HASH)); assertTrue(sessions.attach(0, id, HASH, model));
        sessions.clear(0);
        assertNull(sessions.get(0)); // No accepted animation must still allow immediate channel removal.
        assertSame(model, sessions.offer(2, id, HASH)); assertEquals(37, sessions.get(2).pose);
        sessions.reset(); sessions.reset(); assertEquals(List.of(model), disposed);
    }

    @Test void newProfileSharingTheAssetCannotStealOrResetTheOutgoingSession() {
        var disposed = new ArrayList<Model>();
        var sessions = new ClientModelSessions<Model>(8, 100, ignored -> new Model(0), ignored -> 10, disposed::add);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        var original = new Model(37); sessions.offer(0, a, HASH); sessions.attach(0, a, HASH, original);
        Model second = sessions.offer(0, b, HASH);
        assertNotSame(original, second); assertEquals(0, second.pose); second.pose = 21;
        assertSame(original, sessions.offer(2, a, HASH)); assertEquals(37, original.pose);
        Model third = sessions.offer(0, c, HASH);
        assertNotSame(second, third); assertSame(second, sessions.offer(4, b, HASH));
        assertSame(original, sessions.offer(0, a, HASH)); assertNull(sessions.get(2));
        sessions.clear(2); // A delayed Clear for the old outgoing channel cannot remove the reclaimed instance.
        assertSame(original, sessions.get(0)); assertEquals(37, original.pose);
        assertFalse(sessions.matches(2, a, HASH)); assertTrue(sessions.matches(0, a, HASH));
        sessions.reset(); assertEquals(3, disposed.size()); assertEquals(3, new HashSet<>(disposed).size());
    }

    @Test void pendingOrSupersededUploadsCannotReplaceAnAlreadyResolvedPresentation() {
        var sessions = new ClientModelSessions<Model>(8, 100, ignored -> new Model(0), ignored -> 10, ignored -> {});
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        sessions.offer(0, a, HASH); sessions.offer(0, b, HASH);
        assertFalse(sessions.attach(0, a, HASH, new Model(1)));
        Model model = new Model(2); assertTrue(sessions.attach(0, b, HASH, model));
        assertFalse(sessions.attach(0, b, HASH, new Model(3))); assertSame(model, sessions.get(0));
        sessions.reset();
    }

    @Test void movingAPendingSessionRevokesTheOldChannelsUploadAndOwnership() {
        var sessions = new ClientModelSessions<Model>(8, 100, ignored -> new Model(0), ignored -> 10, ignored -> {});
        UUID id = UUID.randomUUID();
        assertNull(sessions.offer(0, id, HASH)); assertNull(sessions.offer(2, id, HASH));
        assertFalse(sessions.matches(0, id, HASH)); assertFalse(sessions.accepts(0, id, HASH));
        assertTrue(sessions.matches(2, id, HASH)); assertTrue(sessions.attach(2, id, HASH, new Model(1)));
        sessions.reset();
    }

    @Test void cacheEvictionAndOversizedModelsDisposeOnlyDetachedPresentations() {
        var disposed = new ArrayList<Model>();
        var sessions = new ClientModelSessions<Model>(1, 20, ignored -> new Model(10), model -> model.pose, disposed::add);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        Model first = new Model(10), second = new Model(10), huge = new Model(21);
        sessions.offer(0, a, HASH); sessions.attach(0, a, HASH, first); sessions.clear(0);
        sessions.offer(1, b, "b".repeat(64)); sessions.attach(1, b, "b".repeat(64), second); sessions.clear(1);
        assertEquals(List.of(first), disposed);
        sessions.offer(0, c, "c".repeat(64)); sessions.attach(0, c, "c".repeat(64), huge); sessions.clear(0);
        assertEquals(List.of(first, huge), disposed);
        sessions.reset(); assertEquals(List.of(first, huge, second), disposed);
    }

    @Test void unequipMigrationMatchesUninterruptedPlaybackAtEveryClientRefreshRate() {
        for (int fps : new int[] {30, 60, 144, 240}) {
            var document = JsonParser.parseString(ClientAnimationTest.document()).getAsJsonObject();
            var moved = new ClientAnimation(document); var uninterrupted = new ClientAnimation(document);
            var sessions = new ClientModelSessions<ClientAnimation>(8, 100, ClientAnimation::fresh, ignored -> 1, ignored -> {});
            UUID id = UUID.randomUUID(); var bone = new ClientProtocol.Bone("bone", true);
            long start = 1_000_000_000L, switchAt = start + 300_000_000L;
            var initial = new ClientProtocol.Frame(0, id, HASH, 1, true, new int[] {42}, List.of(bone),
                ClientProtocol.View.defaults(), "{\"loop\":{\"name\":\"move\",\"epoch\":1,\"elapsed\":0}}");
            sessions.offer(0, id, HASH); sessions.attach(0, id, HASH, moved);
            moved.receive(initial, start, MolangContext.defaults()); uninterrupted.receive(initial, start, MolangContext.defaults());
            var displayed = moved.matrices(switchAt, MolangContext.defaults(), List.of(bone)).get("bone");
            uninterrupted.matrices(switchAt, MolangContext.defaults(), List.of(bone));
            sessions.clear(0); assertSame(moved, sessions.offer(2, id, HASH));
            var action = new ClientProtocol.Frame(2, id, HASH, 2, true, new int[] {42}, List.of(bone),
                ClientProtocol.View.defaults(), "{\"action\":{\"name\":\"move\",\"epoch\":2,\"elapsed\":0,\"crossfade\":true},"
                + "\"transition\":123,\"transitionRemaining\":0.2}");
            moved.receive(action, switchAt, MolangContext.defaults()); uninterrupted.receive(action, switchAt, MolangContext.defaults());
            assertTrue(displayed.equals(moved.matrices(switchAt, MolangContext.defaults(), List.of(bone)).get("bone"), 1e-6f),
                "Moving to the outgoing channel must preserve the transition's starting pose");
            for (int i = 1; i < fps; i++) {
                long now = switchAt + Math.round(i * 1e9 / fps);
                var actual = sessions.get(2).matrices(now, MolangContext.defaults(), List.of(bone)).get("bone");
                var expected = uninterrupted.matrices(now, MolangContext.defaults(), List.of(bone)).get("bone");
                assertTrue(expected.equals(actual, 1e-6f), "Unequip continuity at " + fps + " FPS");
            }
            sessions.reset();
        }
    }

    @Test void realLegacyUnequipAndModernReverseEquipKeepTheirLastViewportSampleAcrossChannels() throws Exception {
        for (String[] asset : new String[][] {
            {"/legacy-client/arm_generic.json", "default_idle", "default_unequip", "1"},
            {"/legacy-client/arm_rifle.json", "base", "unequip", "1"},
            {"/modern-client/raygun2.json", "base", "equip", "-1"}
        }) {
            byte[] bytes;
            try (var stream = getClass().getResourceAsStream(asset[0])) { bytes = Objects.requireNonNull(stream).readAllBytes(); }
            var document = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("animation");
            var model = ClientModel.parse(bytes); var reference = new ClientAnimation(document);
            List<ClientProtocol.Bone> bones = new ArrayList<>();
            for (var group : document.getAsJsonArray("groups"))
                bones.add(new ClientProtocol.Bone(group.getAsJsonObject().get("uuid").getAsString(), true));
            var sessions = new ClientModelSessions<ClientModel>(8, 64L * 1024 * 1024,
                ClientModel::freshPresentation, ClientModel::cacheBytes, ClientModel::close);
            UUID id = UUID.randomUUID(); long start = 1_000_000_000L, switchAt = start + 300_000_000L;
            var idle = new ClientProtocol.Frame(0, id, HASH, 1, true, new int[] {42}, bones,
                ClientProtocol.View.defaults(), "{\"loop\":{\"name\":\"" + asset[1] + "\",\"epoch\":1,\"elapsed\":0}}");
            sessions.offer(0, id, HASH); sessions.attach(0, id, HASH, model);
            model.receive(idle, start, MolangContext.defaults()); reference.receive(idle, start, MolangContext.defaults());
            model.frameMatrices = model.animation.matrices(switchAt, MolangContext.defaults(), bones);
            var lastViewportSample = model.frameMatrices;
            reference.matrices(switchAt, MolangContext.defaults(), bones);
            // The new profile uses the same bundle; the old one must remain independently reclaimable.
            var incoming = sessions.offer(0, UUID.randomUUID(), HASH);
            assertNotSame(model, incoming); assertNotSame(model.animation, incoming.animation);
            assertTrue(incoming.frameMatrices.isEmpty());
            assertSame(model, sessions.offer(2, id, HASH)); assertSame(lastViewportSample, model.frameMatrices);
            var unequip = new ClientProtocol.Frame(2, id, HASH, 2, true, new int[] {42}, bones, ClientProtocol.View.defaults(),
                "{\"action\":{\"name\":\"" + asset[2] + "\",\"epoch\":2,\"elapsed\":0,\"speed\":" + asset[3]
                + ",\"crossfade\":true},\"transition\":123,\"transitionRemaining\":0.1}");
            model.receive(unequip, switchAt, MolangContext.defaults()); reference.receive(unequip, switchAt, MolangContext.defaults());
            for (int i = 0; i < 144; i++) {
                long now = switchAt + Math.round(i * 1e9 / 144);
                var expected = reference.matrices(now, MolangContext.defaults(), bones);
                var actual = model.animation.matrices(now, MolangContext.defaults(), bones);
                assertEquals(expected.keySet(), actual.keySet());
                for (String bone : expected.keySet())
                    assertTrue(expected.get(bone).equals(actual.get(bone), 1e-5f), asset[0] + " bone " + bone + " frame " + i);
            }
            sessions.reset(); incoming.close(); model.close(); // Closing a presentation twice is harmless.
        }
    }
}
