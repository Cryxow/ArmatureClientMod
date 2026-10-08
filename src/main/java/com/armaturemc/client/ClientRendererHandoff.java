package com.armaturemc.client;

import com.armaturemc.client.protocol.ClientProtocol;

/** Preference and effective ownership are distinct while a resource reload is in flight. */
final class ClientRendererHandoff {
    private boolean active;
    private ClientProtocol.RendererSwitch pending;
    private boolean acknowledged;
    private boolean failed;
    ClientRendererHandoff(boolean active) { this.active = active; }
    boolean active() { return active; }
    boolean pending() { return pending != null; }
    void prepare(ClientProtocol.RendererSwitch message) { pending = message; acknowledged = false; }
    ClientProtocol.RendererReady ready(boolean reloading, boolean packPresent) {
        if (pending == null || acknowledged || reloading) return null;
        if (!pending.pack().equals(ClientProtocol.NO_PACK) && packPresent == pending.client()) return null;
        acknowledged = true; return new ClientProtocol.RendererReady(pending.token());
    }
    boolean commit(ClientProtocol.RendererCommit message) {
        if (pending == null || !pending.token().equals(message.token())) return false;
        active = message.client(); pending = null; return true;
    }
    java.util.UUID pack() { return pending == null ? ClientProtocol.NO_PACK : pending.pack(); }
    void reset(boolean active) { this.active = active; pending = null; acknowledged = false; failed = false; }
    void fail() { reset(false); failed = true; }
    void preferenceChanged() { failed = false; }
    int helloVersion(int preference) { return failed ? 0 : preference; }
    static boolean frameFresh(long now, long received, boolean reloading, long reloadFinished) {
        return reloading || now - received <= ClientProtocol.FRAME_TIMEOUT_NANOS
            || reloadFinished != 0 && now - reloadFinished <= ClientProtocol.FRAME_TIMEOUT_NANOS;
    }
}
