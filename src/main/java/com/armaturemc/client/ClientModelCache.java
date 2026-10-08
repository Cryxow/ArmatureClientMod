package com.armaturemc.client;

import java.util.LinkedHashMap;
import java.util.function.Consumer;

/** Client-thread cache of inactive models. Taking an entry transfers ownership to a hand. */
final class ClientModelCache<T> {
    private record Key(int slot, String hash) { }
    private record Entry<T>(T model, long bytes) { }
    private final LinkedHashMap<Key, Entry<T>> entries = new LinkedHashMap<>();
    private final int capacity;
    private final long byteBudget;
    private final Consumer<T> dispose;
    private long bytes;

    ClientModelCache(int capacity, long byteBudget, Consumer<T> dispose) {
        this.capacity = capacity; this.byteBudget = byteBudget; this.dispose = dispose;
    }

    T take(int slot, String hash) {
        Entry<T> entry = entries.remove(new Key(slot, hash));
        // A retired current model can become an outgoing model without another upload.
        // Only inactive entries are movable; an active hand never shares its mutable playback.
        if (entry == null) {
            var iterator = entries.entrySet().iterator();
            while (iterator.hasNext()) {
                var candidate = iterator.next();
                if (candidate.getKey().hash().equals(hash)) {
                    entry = candidate.getValue(); iterator.remove(); break;
                }
            }
        }
        if (entry == null) return null;
        bytes -= entry.bytes(); return entry.model();
    }

    void put(int slot, String hash, T model, long weight) {
        Key key = new Key(slot, hash);
        Entry<T> previous = entries.remove(key);
        if (previous != null) { bytes -= previous.bytes(); dispose.accept(previous.model()); }
        if (weight > byteBudget || capacity == 0) { dispose.accept(model); return; }
        while (!entries.isEmpty() && (entries.size() >= capacity || bytes + weight > byteBudget)) {
            var oldest = entries.entrySet().iterator();
            Entry<T> entry = oldest.next().getValue(); oldest.remove();
            bytes -= entry.bytes(); dispose.accept(entry.model());
        }
        entries.put(key, new Entry<>(model, weight)); bytes += weight;
    }

    void clear() {
        entries.values().forEach(entry -> dispose.accept(entry.model()));
        entries.clear(); bytes = 0;
    }
}
