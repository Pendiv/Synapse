package dev.div.synapse.http.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dev.div.synapse.Synapse;
import dev.div.synapse.config.AuthToken;
import dev.div.synapse.http.EndpointResult;
import dev.div.synapse.http.SynapseEndpoint;
import dev.div.synapse.http.SynapseError;
import dev.div.synapse.http.SynapseHttpServer;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * {@code GET /manifest} — the self-describing capability dump (spec §5.1).
 * One call gives the AI every endpoint, parameter, the state schema, the error
 * codes, and usage tips, so no external docs or tutorial are needed.
 */
public final class ManifestHandler implements SynapseEndpoint {

    public static final String MC_VERSION = "1.20.1";

    private final List<SynapseEndpoint> functional;

    public ManifestHandler(List<SynapseEndpoint> functional) {
        this.functional = functional;
    }

    @Override
    public String path() {
        return "/manifest";
    }

    @Override
    public String[] methods() {
        return new String[]{"GET"};
    }

    @Override
    public EndpointResult handle(HttpExchange exchange) {
        JsonObject data = new JsonObject();
        data.addProperty("mod", "Synapse");
        data.addProperty("version", modVersion());
        data.addProperty("mcVersion", MC_VERSION);
        data.addProperty("purpose", "AI-facing window into a running Minecraft client. Observe logical "
                + "state via /state, verify visuals via /screenshot, act via /cmd. Prefer /state (JSON "
                + "ground truth) over /screenshot for logic.");

        JsonObject auth = new JsonObject();
        auth.addProperty("required", AuthToken.enabled());
        auth.addProperty("header", SynapseHttpServer.AUTH_HEADER);
        data.add("auth", auth);

        data.add("responseEnvelope", responseEnvelope());

        JsonArray endpoints = new JsonArray();
        endpoints.add(manifestFragment());
        for (SynapseEndpoint e : functional) {
            endpoints.add(e.manifestFragment());
        }
        data.add("endpoints", endpoints);

        data.add("stateSchema", stateSchema());
        data.add("errorCodes", errorCodes());
        data.add("tips", tips());

        return EndpointResult.json(data);
    }

    @Override
    public JsonObject manifestFragment() {
        JsonObject f = new JsonObject();
        f.addProperty("path", "/manifest");
        f.addProperty("method", "GET");
        f.addProperty("desc", "Self-describing capability dump. Call once at startup to learn the whole API.");
        f.addProperty("returns", "This document.");
        return f;
    }

    // === sections ===

    private static JsonObject responseEnvelope() {
        JsonObject o = new JsonObject();
        o.addProperty("ok", "boolean — success flag");
        o.addProperty("endpoint", "string — the path that was hit (for log correlation)");
        o.addProperty("data", "object|null — endpoint-specific payload (null on error)");
        o.addProperty("error", "object|null — { code, message, hint?, currentScreen?, suggestions[] } on failure");
        o.addProperty("logs", "string[] — recent log lines (size = config logBufferSize)");
        o.addProperty("context", "object — { inWorld, screen, dimension } returned every call");
        o.addProperty("ts", "number — server epoch millis");
        return o;
    }

    private JsonObject stateSchema() {
        JsonObject o = new JsonObject();
        o.addProperty("summary", "dimension, pos{x,y,z,block[]}, rotation{yaw,pitch}, health, food, gameMode, "
                + "lookingAt{type:block|entity|miss, block?, blockPos?, face?, entity?}, screen, "
                + "hotbar[{slot,item,count}], selectedSlot");
        o.addProperty("full", "summary + inventory{main[],armor[],offhand}, openContainer?, attributes, "
                + "effects[{effect,amplifier,duration}], experience{level,progress,total}, "
                + "world{time,dayTime,weather,biome,lightLevel{block,sky},difficulty}, "
                + "nearbyEntities[{type,id,pos[],distance,health?}], raycast, fps, renderDistance");
        o.addProperty("note", "Call GET /state?mode=full for the concrete shape with live values.");
        return o;
    }

    private static JsonArray errorCodes() {
        JsonArray arr = new JsonArray();
        for (SynapseError e : SynapseError.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("code", e.code());
            o.addProperty("httpStatus", e.httpStatus);
            o.addProperty("meaning", e.description);
            arr.add(o);
        }
        return arr;
    }

    private static JsonArray tips() {
        JsonArray arr = new JsonArray();
        arr.add("Use /state to confirm logic; use /screenshot only to confirm visuals.");
        arr.add("Out of world (menu screens) /cmd and /state return NOT_IN_WORLD — check context.inWorld first.");
        arr.add("/cmd takes a raw command (leading slash optional) and runs at permission level 4.");
        arr.add("Every response carries recent logs and a lightweight context — read them to self-correct.");
        arr.add("Batch operations (/batch) are planned for v1.1; for now issue commands sequentially.");
        return arr;
    }

    private static String modVersion() {
        try {
            return ModList.get().getModContainerById(Synapse.MODID)
                    .map(c -> c.getModInfo().getVersion().toString())
                    .orElse("unknown");
        } catch (Throwable t) {
            return "unknown";
        }
    }
}
