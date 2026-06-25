package dev.div.synapse.core;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

/**
 * Builds the lightweight {@code context} object attached to every response
 * (spec §4): {@code { inWorld, screen, dimension }}. Best-effort — never throws,
 * so a stuck/paused game degrades to a minimal context instead of breaking the
 * response.
 */
public final class ContextCollector {

    private ContextCollector() {
    }

    /** Collects context on the main thread; returns a fallback object on any failure. */
    public static JsonObject collectSafe(long timeoutMs) {
        try {
            return MainThread.run(ContextCollector::collect, timeoutMs);
        } catch (Throwable t) {
            JsonObject o = new JsonObject();
            o.addProperty("inWorld", false);
            o.addProperty("screen", "unknown");
            o.add("dimension", JsonNull.INSTANCE);
            o.addProperty("note", "context unavailable: " + t.getClass().getSimpleName());
            return o;
        }
    }

    private static JsonObject collect() {
        Minecraft mc = Minecraft.getInstance();
        JsonObject o = new JsonObject();
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
