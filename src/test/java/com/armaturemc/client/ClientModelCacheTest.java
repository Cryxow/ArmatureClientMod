package com.armaturemc.client;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClientModelCacheTest {
    @Test void reequippingTakesTheUploadedModelWithoutDestroyingOrSharingItsHandState() {
        var disposed = new ArrayList<String>();
        var cache = new ClientModelCache<String>(8, 100, disposed::add);
        cache.put(0, "hash", "main", 30); cache.put(2, "hash", "outgoing", 30);
        assertNull(cache.take(1, "unknown"));
        assertEquals("main", cache.take(0, "hash"));
        cache.clear();
        assertEquals(java.util.List.of("outgoing"), disposed);
    }

    @Test void retiredModelMovesToOutgoingChannelWithoutSharingAnActiveInstance() {
        var disposed = new ArrayList<String>();
        var cache = new ClientModelCache<String>(8, 100, disposed::add);
        cache.put(0, "hash", "model", 30);
        assertEquals("model", cache.take(2, "hash"));
        assertNull(cache.take(0, "hash")); assertNull(cache.take(2, "hash"));
        cache.clear(); assertTrue(disposed.isEmpty());
        cache.put(2, "hash", "model", 30); // The outgoing channel later retires it.
        assertEquals("model", cache.take(0, "hash"));
        cache.clear(); assertTrue(disposed.isEmpty());
    }

    @Test void evictsOldestInactiveModelsByBytesAndCountAndFreesRejectedTextures() {
        var disposed = new ArrayList<String>();
        var cache = new ClientModelCache<String>(2, 100, disposed::add);
        cache.put(0, "a", "a", 40); cache.put(0, "b", "b", 40);
        cache.put(0, "c", "c", 70); // Both old entries exceed the byte budget with c.
        assertEquals(java.util.List.of("a", "b"), disposed);
        cache.put(0, "huge", "huge", 101);
        assertEquals(java.util.List.of("a", "b", "huge"), disposed);
        cache.put(1, "d", "d", 10); cache.put(1, "e", "e", 10); // Count limit evicts c.
        assertNull(cache.take(0, "c")); assertEquals("d", cache.take(1, "d"));
        cache.clear(); cache.clear();
        assertEquals(java.util.List.of("a", "b", "huge", "c", "e"), disposed);
    }

    @Test void replacingSameSlotAndHashDisposesExactlyThePreviousAllocation() {
        var disposed = new ArrayList<String>();
        var cache = new ClientModelCache<String>(2, 100, disposed::add);
        cache.put(0, "hash", "old", 80); cache.put(0, "hash", "new", 90);
        assertEquals(java.util.List.of("old"), disposed);
        assertEquals("new", cache.take(0, "hash"));
        cache.clear(); assertEquals(java.util.List.of("old"), disposed);
    }
}
