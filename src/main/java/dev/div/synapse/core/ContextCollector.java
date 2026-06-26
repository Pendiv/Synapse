package dev.div.synapse.core;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Builds the lightweight {@code context} object attached to every response
 * (spec §4): {@code { inWorld, screen, dimension }}.
 *
 * <p>Rather than hop onto the main thread on every request (a second main-thread
 * round-trip on top of the endpoint's own work), {@link TickClock} calls
 * {@link #capture()} once per client tick to refresh a snapshot, and
 * {@link #snapshot()} returns it without touching the game thread. The context is
 * therefore at most one tick (~50 ms) stale — fine for a "where am I" hint.
 */
public final class ContextCollector {

    private static final AtomicReference<JsonObject> SNAPSHOT = new AtomicReference<>();

    private ContextCollector() {
    }

    /** Refreshes the cached context. Called on the client thread every tick (incl. menus). */
    public static void capture() {
        try {
            SNAPSHOT.set(collect());
        } catch (Throwable ignored) {
            // Leave the previous snapshot in place on any transient failure.
        }
    }

    /** The most recent context snapshot (a copy), or a minimal fallback if none captured yet. */
    public static JsonObject snapshot() {
        JsonObject snap = SNAPSHOT.get();
        if (snap != null) {
            return snap.deepCopy();
        }
        JsonObject o = new JsonObject();
        o.addProperty("inWorld", false);
        o.addProperty("screen", "unknown");
        o.add("dimension", JsonNull.INSTANCE);
        o.addProperty("note", "context not yet captured");
        return o;
    }

    private static JsonObject collect() {
        Minecraft mc = Minecraft.getInstance();
        JsonObject o = new JsonObject();
        // Capture time so a consumer can tell how fresh this snapshot is (it stops advancing if the
        // client thread stalls), distinguishing a live snapshot from a frozen one.
        o.addProperty("capturedAtMs", System.currentTimeMillis());
        o.addProperty("inWorld", mc.player != null && mc.level != null);
        o.addProperty("screen", mc.screen == null ? "none" : mc.screen.getClass().getSimpleName());
        if (mc.level != null) {
            o.addProperty("dimension", mc.level.dimension().location().toString());
        } else {
            o.add("dimension", JsonNull.INSTANCE);
        }
        return o;
    }
}
