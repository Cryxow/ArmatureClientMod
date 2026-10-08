package com.armaturemc.client;

import com.armaturemc.client.protocol.ClientProtocol;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ClientRenderingControlTest {
    @TempDir Path directory;

    @Test void disablePersistsBeforeClearingOwnershipAndReleasesThePluginLease() throws Exception {
        Path path = directory.resolve("armature-client.json");
        List<String> events = new ArrayList<>();
        var control = new ClientRenderingControl(path, () -> {
            try { assertTrue(Files.readString(path).contains("false")); }
            catch (IOException failure) { throw new AssertionError(failure); }
            events.add("clear");
        }, version -> events.add("hello:" + version));
        control.load(); assertTrue(control.enabled());
        control.setEnabled(false);
        assertFalse(control.enabled()); assertEquals(0, control.helloVersion());
        assertEquals(List.of("clear", "hello:0"), events);
        var restarted = new ClientRenderingControl(path, () -> fail("Loading should not negotiate"), ignored -> fail());
        restarted.load(); assertFalse(restarted.enabled());
        control.setEnabled(false); assertEquals(2, events.size());
    }

    @Test void enableAfterRestartStartsAFreshCompatibleNegotiation() throws Exception {
        Path path = directory.resolve("armature-client.json");
        Files.writeString(path, "{\"renderingEnabled\":false}");
        var events = new ArrayList<Integer>();
        var control = new ClientRenderingControl(path, () -> events.add(-1), events::add);
        control.load(); control.setEnabled(true);
        assertEquals(List.of(-1, ClientProtocol.VERSION), events);
        var restarted = new ClientRenderingControl(path, () -> {}, ignored -> {});
        restarted.load(); assertTrue(restarted.enabled());
    }

    @Test void failedSaveDoesNotChangeTheRendererOrContactTheServer() throws Exception {
        Path blockingFile = directory.resolve("file"); Files.writeString(blockingFile, "occupied");
        var control = new ClientRenderingControl(blockingFile.resolve("config.json"),
            () -> fail("Must retain renderer on failed save"), ignored -> fail("Must retain server lease"));
        assertThrows(IOException.class, () -> control.setEnabled(false));
        assertTrue(control.enabled());
        assertEquals("occupied", Files.readString(blockingFile));
    }

    @Test void malformedPreferencesKeepTheDefaultAndAreNotOverwrittenDuringLoad() throws Exception {
        Path path = directory.resolve("armature-client.json"); String invalid = "{\"renderingEnabled\":\"false\"}";
        Files.writeString(path, invalid);
        var control = new ClientRenderingControl(path, () -> fail(), ignored -> fail());
        assertThrows(IOException.class, control::load); assertTrue(control.enabled());
        assertEquals(invalid, Files.readString(path));
    }
}
