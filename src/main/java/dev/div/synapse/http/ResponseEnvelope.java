package dev.div.synapse.http;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/**
 * Builds the common response envelope returned by every endpoint (spec §4):
 * {@code { ok, endpoint, data, error, logs, context, ts }}.
 */
public final class ResponseEnvelope {

    /**
     * Shared serialiser. HTML escaping is disabled so output stays human/AI readable,
     * and nulls are serialised so the envelope always carries explicit
     * {@code data}/{@code error}/{@code context.dimension} keys (spec §4), even when null.
     */
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    private ResponseEnvelope() {
    }

    /**
     * Assembles the full envelope JSON string.
     *
     * @param endpoint the path that was hit (for the AI's log correlation)
     * @param ok       success flag
     * @param data     endpoint body on success, may be {@code null}
     * @param error    error object on failure (see {@link SynapseException#toJson()}), may be {@code null}
     * @param logs     recent log lines, may be {@code null}
     * @param context  lightweight always-on context, may be {@code null}
     * @param ts       server epoch millis
     */
    public static String render(String endpoint, boolean ok, JsonElement data, JsonObject error,
                                JsonArray logs, JsonObject context, long ts) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", ok);
        o.addProperty("endpoint", endpoint);
        o.add("data", data == null ? JsonNull.INSTANCE : data);
        o.add("error", error == null ? JsonNull.INSTANCE : error);
        o.add("logs", logs == null ? new JsonArray() : logs);
        o.add("context", context == null ? new JsonObject() : context);
        o.addProperty("ts", ts);
        return GSON.toJson(o);
    }
}
