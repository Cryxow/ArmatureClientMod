package com.armaturemc.client;

import com.armaturemc.client.protocol.ClientProtocol;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClientRendererHandoffTest {
    @Test void modelFailureCannotCancelSlowFallbackLoadingWithAutomaticHelloRenewals() {
        var handoff = new ClientRendererHandoff(true); handoff.fail();
        assertEquals(0, handoff.helloVersion(ClientProtocol.VERSION));
        UUID token = UUID.randomUUID();
        handoff.prepare(new ClientProtocol.RendererSwitch(token, UUID.randomUUID(), false));
        handoff.commit(new ClientProtocol.RendererCommit(token, false));
        assertEquals(0, handoff.helloVersion(ClientProtocol.VERSION));
        handoff.preferenceChanged(); assertEquals(ClientProtocol.VERSION, handoff.helloVersion(ClientProtocol.VERSION));
    }
    @Test void slowResourceReloadCannotReleaseHandMaskBeforeFreshServerFramesResume() {
        long received = 1_000_000_000L, now = received + 5_000_000_000L;
        assertFalse(ClientRendererHandoff.frameFresh(now, received, false, 0));
        assertTrue(ClientRendererHandoff.frameFresh(now, received, true, 0));
        assertTrue(ClientRendererHandoff.frameFresh(now + 500_000_000L, received, false, now));
        assertFalse(ClientRendererHandoff.frameFresh(now + 1_100_000_000L, received, false, now));
    }
    @Test void disableContinuesRenderingThroughFullReloadAndCommitsOnlyMatchingToken() {
        var handoff = new ClientRendererHandoff(true); UUID token = UUID.randomUUID();
        handoff.prepare(new ClientProtocol.RendererSwitch(token, UUID.randomUUID(), false));
        assertTrue(handoff.active()); assertNull(handoff.ready(true, true)); assertNull(handoff.ready(false, false));
        assertEquals(new ClientProtocol.RendererReady(token), handoff.ready(false, true));
        assertNull(handoff.ready(false, true));
        assertFalse(handoff.commit(new ClientProtocol.RendererCommit(UUID.randomUUID(), false)));
        assertTrue(handoff.commit(new ClientProtocol.RendererCommit(token, false))); assertFalse(handoff.active());
    }
    @Test void enableWaitsForRendererPackAbsenceAndShaderReloadCompletion() {
        var handoff = new ClientRendererHandoff(false); UUID token = UUID.randomUUID();
        handoff.prepare(new ClientProtocol.RendererSwitch(token, UUID.randomUUID(), true));
        assertNull(handoff.ready(false, true)); assertNull(handoff.ready(true, false));
        assertNotNull(handoff.ready(false, false)); assertFalse(handoff.active());
        handoff.commit(new ClientProtocol.RendererCommit(token, true)); assertTrue(handoff.active());
    }
    @Test void cancelledSwitchCannotApplyLateCommitAndFailureRetainsCurrentRenderer() {
        var handoff = new ClientRendererHandoff(true); UUID old = UUID.randomUUID(), token = UUID.randomUUID();
        handoff.prepare(new ClientProtocol.RendererSwitch(old, UUID.randomUUID(), false));
        handoff.prepare(new ClientProtocol.RendererSwitch(token, UUID.randomUUID(), true));
        assertFalse(handoff.commit(new ClientProtocol.RendererCommit(old, false)));
        assertTrue(handoff.commit(new ClientProtocol.RendererCommit(token, true))); assertTrue(handoff.active());
    }
    @Test void singlePackServersCanHandoffWithoutResourceReload() {
        var handoff = new ClientRendererHandoff(true); UUID token = UUID.randomUUID();
        handoff.prepare(new ClientProtocol.RendererSwitch(token, ClientProtocol.NO_PACK, false));
        assertNotNull(handoff.ready(false, false));
    }
}
