package com.armaturemc.client;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Client-thread ownership of one uploaded texture bundle across independent presentations. */
final class ClientSharedTextures<T> {
    private final Map<String, T> textures = new HashMap<>();
    private final Consumer<T> release;
    private int owners = 1;

    ClientSharedTextures(Consumer<T> release) { this.release = release; }
    ClientSharedTextures<T> retain() {
        if (owners == 0) throw new IllegalStateException("Texture bundle already released");
        owners++; return this;
    }
    T get(String id) { return textures.get(id); }
    void put(String id, T texture) { textures.put(id, texture); }
    void close() {
        if (owners == 0) throw new IllegalStateException("Unbalanced texture ownership");
        if (--owners == 0) { textures.values().forEach(release); textures.clear(); }
    }
}
