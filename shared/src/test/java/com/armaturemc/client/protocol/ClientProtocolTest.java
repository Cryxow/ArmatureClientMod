package com.armaturemc.client.protocol;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClientProtocolTest {
    @Test void heldItemsAreBoundedCopiedAndScopedToOnePresentation() throws Exception {
        byte[] main = {1, 2, 3};
        var session = UUID.randomUUID();
        var original = new ClientProtocol.HeldItems(2, session, main, new byte[0]);
        main[0] = 9;
        var decoded = (ClientProtocol.HeldItems) ClientProtocol.decode(ClientProtocol.encode(original));
        assertEquals(session, decoded.session());
        assertEquals(2, decoded.slot());
        assertArrayEquals(new byte[]{1, 2, 3}, decoded.main());
        assertEquals(0, decoded.off().length);
        decoded.main()[0] = 8;
        assertEquals(1, decoded.main()[0]);
        var maximum = new ClientProtocol.HeldItems(3, session,
            new byte[ClientProtocol.MAX_HELD_ITEM], new byte[ClientProtocol.MAX_HELD_ITEM]);
        assertTrue(ClientProtocol.encode(maximum).length < ClientProtocol.MAX_PACKET);
        assertThrows(IllegalArgumentException.class, () -> new ClientProtocol.HeldItems(0, session,
            new byte[ClientProtocol.MAX_HELD_ITEM + 1], new byte[0]));
        byte[] bytes = ClientProtocol.encode(original);
        assertThrows(Exception.class, () -> ClientProtocol.decode(Arrays.copyOf(bytes, bytes.length - 1)));
    }
    @Test void rendererReloadHandshakePreservesPackIdentityAndTransitionTokens() throws Exception {
        UUID token = UUID.randomUUID(), pack = UUID.randomUUID();
        for (var message : List.of(new ClientProtocol.RendererSwitch(token, pack, true),
                new ClientProtocol.RendererReady(token), new ClientProtocol.RendererCommit(token, false))) {
            assertEquals(message, ClientProtocol.decode(ClientProtocol.encode(message)));
            byte[] bytes = ClientProtocol.encode(message);
            assertThrows(Exception.class, () -> ClientProtocol.decode(Arrays.copyOf(bytes, bytes.length - 1)));
        }
        assertThrows(IllegalArgumentException.class, () -> ClientProtocol.decode(new byte[]{0,0,0,6,0,0,0,0,6}));
    }
    @Test void configurationIsBoundedAndIndependentOfMaximumBoneFrames() throws Exception {
        var options = new ClientProtocol.Options(2, "{\"motion\":{\"enabled\":false}}");
        assertEquals(options, ClientProtocol.decode(ClientProtocol.encode(options)));
        assertThrows(IllegalArgumentException.class, () -> new ClientProtocol.Options(0, "x".repeat(8193)));
    }
    @Test void pluginPayloadIsExactlyTheFabricPayloadWithoutALengthPrefix() throws Exception {
        byte[] bytes = ClientProtocol.encode(new ClientProtocol.Hello(ClientProtocol.VERSION));
        assertArrayEquals(new byte[]{0,0,0,9,0,0,0,0,9}, bytes);
        assertEquals(new ClientProtocol.Hello(ClientProtocol.VERSION), ClientProtocol.decode(bytes));
    }
    @Test void previousPeersCannotNegotiateOwnershipOffersAndEarlyCachedReadiness() {
        assertThrows(IllegalArgumentException.class, () ->
            ClientProtocol.decode(new byte[]{0,0,0,8,0,0,0,0,8}));
        assertThrows(IllegalArgumentException.class, () ->
            ClientProtocol.decode(new byte[]{0,0,0,4,0,0,0,0,4}));
        assertThrows(IllegalArgumentException.class, () ->
            ClientProtocol.decode(new byte[]{0,0,0,5,0,0,0,0,5}));
    }

    @Test void everyHotbarOutgoingChannelCanRetainItsOwnSessionAndItems() throws Exception {
        for (int slot = 2; slot < ClientProtocol.MODEL_CHANNELS; slot++) {
            UUID session = UUID.randomUUID();
            var frame = new ClientProtocol.Frame(slot, session, "a".repeat(64), 1, true,
                new int[]{100 + slot}, List.of());
            var decoded = (ClientProtocol.Frame) ClientProtocol.decode(ClientProtocol.encode(frame));
            assertEquals(slot, decoded.slot());
            assertEquals(session, decoded.session());
            var items = new ClientProtocol.HeldItems(slot, session, new byte[]{1}, new byte[0]);
            var decodedItems = (ClientProtocol.HeldItems) ClientProtocol.decode(ClientProtocol.encode(items));
            assertEquals(slot, decodedItems.slot());
            assertEquals(session, decodedItems.session());
        }
        assertThrows(IllegalArgumentException.class, () -> ClientProtocol.encode(new ClientProtocol.Clear(ClientProtocol.MODEL_CHANNELS)));
    }
    @Test void framesPreserveSessionVisibilityLocalOffsetsAndVirtualIds() throws Exception {
        float[] offset = new float[]{-.125f, .2f, 0, 0, 0, 0, 1};
        var frame = new ClientProtocol.Frame(1, UUID.randomUUID(), "a".repeat(64), 7, true,
            new int[]{100,200}, List.of(new ClientProtocol.Bone("arm", true, offset)),
            new ClientProtocol.View(110, true, 1.35f, true), "{\"mainItemModel\":\"armature:held/test\"}");
        var result = (ClientProtocol.Frame)ClientProtocol.decode(ClientProtocol.encode(frame));
        assertEquals(frame.session(), result.session()); assertEquals(7, result.sequence());
        assertTrue(result.replaceHand()); assertTrue(result.bones().getFirst().visible());
        assertArrayEquals(frame.entityIds(), result.entityIds());
        assertArrayEquals(offset, result.bones().getFirst().offset());
        assertEquals(frame.view(), result.view());
        assertEquals(frame.control(), result.control());
    }
    @Test void outgoingChannelsAreIndependentAndControlFitsTheMaximumFrameBudget() throws Exception {
        var bones = new ArrayList<ClientProtocol.Bone>();
        for (int i = 0; i < ClientProtocol.MAX_BONES; i++) bones.add(new ClientProtocol.Bone(
            String.format("%036d", i), true, new float[]{1, 2, 3, .5f, .5f, .5f, .5f}));
        var frame = new ClientProtocol.Frame(3, UUID.randomUUID(), "a".repeat(64), 1, true,
            new int[ClientProtocol.MAX_ENTITIES], bones, ClientProtocol.View.defaults(), "x".repeat(1024));
        assertTrue(ClientProtocol.encode(frame).length <= ClientProtocol.MAX_PACKET);
        assertEquals(3, ((ClientProtocol.Frame)ClientProtocol.decode(ClientProtocol.encode(frame))).slot());
        assertThrows(IllegalArgumentException.class, () -> new ClientProtocol.Frame(0, UUID.randomUUID(),
            "a".repeat(64), 1, true, new int[0], List.of(), ClientProtocol.View.defaults(), "x".repeat(1025)));
    }
    @Test void invalidProjectionSettingsCannotReachTheClient() {
        assertThrows(IllegalArgumentException.class, () -> new ClientProtocol.View(Float.NaN, true, 1, true));
        assertThrows(IllegalArgumentException.class, () -> new ClientProtocol.View(180, true, 1, true));
        assertThrows(IllegalArgumentException.class, () -> new ClientProtocol.View(70, true, Float.POSITIVE_INFINITY, true));
    }
    @Test void truncatedAndTrailingPayloadsAreRejected() {
        byte[] bytes = ClientProtocol.encode(new ClientProtocol.Hello(1));
        assertThrows(Exception.class, () -> ClientProtocol.decode(Arrays.copyOf(bytes, bytes.length - 1)));
        assertThrows(IllegalArgumentException.class, () -> ClientProtocol.decode(Arrays.copyOf(bytes, bytes.length + 1)));
    }
    @Test void nonFiniteOffsetsOrInvalidQuaternionsCannotEnterTheRenderer() {
        assertThrows(IllegalArgumentException.class, () -> new ClientProtocol.Bone("arm", true,
            new float[]{Float.NaN, 0, 0, 0, 0, 0, 1}));
        assertThrows(IllegalArgumentException.class, () -> new ClientProtocol.Bone("arm", true, new float[7]));
        assertThrows(IllegalArgumentException.class, () -> new ClientProtocol.Bone("arm", true,
            new float[]{0, 0, 0, 0, 0, 0, 2}));
    }
    @Test void outOfOrderModelChunksCompleteOnlyAfterVerifiedFinalChunk() {
        byte[] bytes = new byte[ClientProtocol.CHUNK_BYTES * 2 + 37]; new Random(42).nextBytes(bytes);
        var transfer = new ModelTransfer(chunk(bytes, 0));
        assertTrue(transfer.accept(chunk(bytes, 2)).isEmpty());
        assertTrue(transfer.accept(chunk(bytes, 0)).isEmpty());
        assertArrayEquals(bytes, transfer.accept(chunk(bytes, 1)).orElseThrow());
    }
    @Test void duplicateOrConflictingChunksCannotPoisonATransfer() {
        byte[] bytes = new byte[ClientProtocol.CHUNK_BYTES + 7];
        var first = chunk(bytes, 0); var transfer = new ModelTransfer(first); transfer.accept(first);
        assertThrows(IllegalArgumentException.class, () -> transfer.accept(first));
        var conflict = new ClientProtocol.ModelChunk(1, first.hash(), first.totalBytes(), 1, new byte[7]);
        assertThrows(IllegalArgumentException.class, () -> transfer.accept(conflict));
    }
    @Test void corruptModelBytesNeverBecomeReady() {
        byte[] bytes = new byte[]{1,2,3}; var first = chunk(bytes, 0);
        var corrupt = new ClientProtocol.ModelChunk(0, first.hash(), 3, 0, new byte[]{4,5,6});
        assertThrows(IllegalArgumentException.class, () -> new ModelTransfer(first).accept(corrupt));
    }
    @Test void modelAndFrameBudgetsAreEnforcedBeforeAllocation() {
        assertThrows(IllegalArgumentException.class, () -> new ModelTransfer(
            new ClientProtocol.ModelChunk(0, "a".repeat(64), ClientProtocol.MAX_MODEL + 1, 0, new byte[0])));
        assertThrows(IllegalArgumentException.class, () -> ClientProtocol.encode(
            new ClientProtocol.Frame(0, UUID.randomUUID(), "a".repeat(64), 1, true,
                new int[ClientProtocol.MAX_ENTITIES + 1], List.of())));
    }
    @Test void decodedPacketsAndPublicRecordsCannotBeMutatedThroughArrays() throws Exception {
        var bone = new ClientProtocol.Bone("bone", true); bone.offset()[0] = 99;
        assertEquals(0, bone.offset()[0]);
        var chunk = chunk(new byte[]{1,2,3}, 0); chunk.bytes()[0] = 9;
        assertEquals(1, chunk.bytes()[0]);
        var result = (ClientProtocol.ModelChunk)ClientProtocol.decode(ClientProtocol.encode(chunk));
        assertArrayEquals(chunk.bytes(), result.bytes());
    }
    private static ClientProtocol.ModelChunk chunk(byte[] bytes, int index) {
        return new ClientProtocol.ModelChunk(0, ClientProtocol.hash(bytes), bytes.length, index,
            Arrays.copyOfRange(bytes, index * ClientProtocol.CHUNK_BYTES,
                Math.min(bytes.length, (index + 1) * ClientProtocol.CHUNK_BYTES)));
    }
}
