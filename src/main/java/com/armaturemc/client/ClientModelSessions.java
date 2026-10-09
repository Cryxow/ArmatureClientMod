package com.armaturemc.client;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/** A channel addresses a presentation; the session owns its mutable playback. */
final class ClientModelSessions<T> {
    private record Key(UUID session, String hash) { }
    private record Presentation<T>(Key key, T model, long bytes) { }
    private final Map<Integer, Presentation<T>> channels = new HashMap<>();
    private final LinkedHashMap<Key, Presentation<T>> cached = new LinkedHashMap<>();
    private final int capacity;
    private final long budget;
    private final Function<T, T> fork;
    private final ToLongFunction<T> weight;
    private final Consumer<T> dispose;
    private long bytes;

    ClientModelSessions(int capacity, long budget, Function<T, T> fork, ToLongFunction<T> weight, Consumer<T> dispose) {
        this.capacity = capacity; this.budget = budget; this.fork = fork; this.weight = weight; this.dispose = dispose;
    }

    T get(int slot) { var value = channels.get(slot); return value == null ? null : value.model(); }

    boolean matches(int slot, UUID session, String hash) {
        var value = channels.get(slot);
        return value != null && value.key().equals(new Key(session, hash));
    }

    T offer(int slot, UUID session, String hash) {
        Key key = new Key(session, hash);
        var current = channels.get(slot);
        if (current != null && current.key().equals(key)) return current.model();
        clear(slot);
        current = null;
        // Reclaim/move the same session before considering any geometry cache hit.
        for (var iterator = channels.entrySet().iterator(); iterator.hasNext();) {
            var candidate = iterator.next();
            if (candidate.getValue().key().equals(key)) {
                current = candidate.getValue(); iterator.remove(); break;
            }
        }
        if (current == null) {
            current = cached.remove(key);
            if (current != null) bytes -= current.bytes();
        }
        if (current == null) {
            T template = null;
            for (var candidate : channels.values())
                if (candidate.model() != null && candidate.key().hash().equals(hash)) { template = candidate.model(); break; }
            if (template == null) for (var candidate : cached.values())
                if (candidate.key().hash().equals(hash)) template = candidate.model();
            // A different session gets fresh playback, but may share already uploaded textures.
            T model = template == null ? null : fork.apply(template);
            current = new Presentation<>(key, model, model == null ? 0 : weight.applyAsLong(model));
        }
        channels.put(slot, current);
        return current.model();
    }

    boolean accepts(int slot, UUID session, String hash) {
        var value = channels.get(slot);
        return value != null && value.model() == null && value.key().equals(new Key(session, hash));
    }

    boolean attach(int slot, UUID session, String hash, T model) {
        if (!accepts(slot, session, hash)) return false;
        channels.put(slot, new Presentation<>(new Key(session, hash), model, weight.applyAsLong(model)));
        return true;
    }

    void clear(int slot) {
        var value = channels.remove(slot);
        if (value == null || value.model() == null) return;
        var previous = cached.remove(value.key());
        if (previous != null) { bytes -= previous.bytes(); dispose.accept(previous.model()); }
        if (capacity == 0 || value.bytes() > budget) { dispose.accept(value.model()); return; }
        while (!cached.isEmpty() && (cached.size() >= capacity || bytes + value.bytes() > budget)) {
            var oldest = cached.entrySet().iterator(); var entry = oldest.next().getValue(); oldest.remove();
            bytes -= entry.bytes(); dispose.accept(entry.model());
        }
        cached.put(value.key(), value); bytes += value.bytes();
    }

    void reset() {
        channels.values().forEach(value -> { if (value.model() != null) dispose.accept(value.model()); });
        cached.values().forEach(value -> dispose.accept(value.model()));
        channels.clear(); cached.clear(); bytes = 0;
    }
}
