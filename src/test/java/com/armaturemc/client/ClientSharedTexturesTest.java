package com.armaturemc.client;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClientSharedTexturesTest {
    @Test void outgoingAndIncomingPresentationsKeepSharedTexturesUntilTheLastOwnerCloses() {
        var released = new ArrayList<String>();
        var outgoing = new ClientSharedTextures<String>(released::add);
        outgoing.put("skin", "uploaded-texture");
        var incoming = outgoing.retain(); var cached = incoming.retain();
        outgoing.close(); incoming.close();
        assertTrue(released.isEmpty()); assertEquals("uploaded-texture", cached.get("skin"));
        cached.close(); assertEquals(List.of("uploaded-texture"), released);
        assertThrows(IllegalStateException.class, cached::retain);
        assertThrows(IllegalStateException.class, cached::close);
    }
}
