package dev.div.synapse.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit tests for the registry's pure upsert/eviction (no FMLPaths / file IO). */
class InstanceRegistryTest {

    private static JsonObject entry(String name, String gamedir, int port) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("gamedir", gamedir);
        o.addProperty("port", port);
        return o;
    }

    private static String name(JsonArray arr, int i) {
        return arr.get(i).getAsJsonObject().get("name").getAsString();
    }

    @Test
    void addsToEmpty() {
        JsonArray arr = new JsonArray();
        InstanceRegistry.upsert(arr, entry("a", "/a", 25599), "/a", 25599);
        assertEquals(1, arr.size());
        assertEquals("a", name(arr, 0));
    }

    @Test
    void evictsSameGamedirOnRestart() {
        JsonArray arr = new JsonArray();
        arr.add(entry("old", "/a", 25601)); // same gamedir, even on a different port
        InstanceRegistry.upsert(arr, entry("new", "/a", 25599), "/a", 25599);
        assertEquals(1, arr.size());
        assertEquals("new", name(arr, 0));
    }

    @Test
    void evictsStaleEntryHoldingOurPort() {
        // A crash-stale 'ghost' still claims the port we just bound -> must be evicted so an
        // AI can't be routed to the wrong world via a reused port.
        JsonArray arr = new JsonArray();
        arr.add(entry("ghost", "/fake", 25599));
        InstanceRegistry.upsert(arr, entry("real", "/b", 25599), "/b", 25599);
        assertEquals(1, arr.size());
        assertEquals("real", name(arr, 0));
    }

    @Test
    void keepsUnrelatedInstances() {
        JsonArray arr = new JsonArray();
        arr.add(entry("other", "/c", 25600));
        InstanceRegistry.upsert(arr, entry("mine", "/d", 25599), "/d", 25599);
        assertEquals(2, arr.size());
    }
}
