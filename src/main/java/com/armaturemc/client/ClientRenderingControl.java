package com.armaturemc.client;

import com.armaturemc.client.protocol.ClientProtocol;
import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.function.IntConsumer;

/** Persistent local preference and ordered renderer handoff; no Minecraft dependency. */
final class ClientRenderingControl {
    private final Path path;
    private final Runnable release;
    private final IntConsumer announce;
    private boolean enabled = true;

    ClientRenderingControl(Path path, Runnable release, IntConsumer announce) {
        this.path = path; this.release = release; this.announce = announce;
    }

    boolean enabled() { return enabled; }
    int helloVersion() { return enabled ? ClientProtocol.VERSION : 0; }

    void load() throws IOException {
        if (!Files.exists(path)) return;
        try {
            var root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            var value = root.get("renderingEnabled");
            if (value != null) {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
                    throw new IllegalArgumentException("renderingEnabled must be a boolean");
                enabled = value.getAsBoolean();
            }
        } catch (RuntimeException malformed) { throw new IOException("Invalid Armature client configuration", malformed); }
    }

    void setEnabled(boolean next) throws IOException {
        if (next == enabled) return;
        JsonObject root = new JsonObject(); root.addProperty("renderingEnabled", next);
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(path.toAbsolutePath().getParent(), "armature-client-", ".tmp");
        try {
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n", StandardCharsets.UTF_8);
            try { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unavailable) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
        // Persist first; a failed save leaves both the running renderer and stored choice unchanged.
        enabled = next;
        release.run();
        announce.accept(helloVersion());
    }
}
