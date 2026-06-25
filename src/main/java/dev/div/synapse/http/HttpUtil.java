package dev.div.synapse.http;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dev.div.synapse.config.SynapseConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Small JDK-HttpServer helpers: body reading, query parsing, response writing. */
public final class HttpUtil {

    private static final int MAX_BODY_FALLBACK = 1 << 20;

    private HttpUtil() {
    }

    /**
     * Reads the request body as UTF-8, capped at {@code maxBodyBytes}. Returns "" if there is none.
     * The bounded read defeats both a lying/absent Content-Length and an unbounded chunked stream,
     * so a single request can no longer exhaust the client heap.
     */
    public static String readBody(HttpExchange exchange) throws IOException, SynapseException {
        int cap = maxBody();
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(cap + 1);
            if (bytes.length > cap) {
                throw new SynapseException(SynapseError.BAD_REQUEST,
                        "Request body exceeds the " + cap + "-byte limit.")
                        .detail("hint", "Commands and JSON payloads are tiny; raise server.maxBodyBytes if you truly need more.");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static int maxBody() {
        try {
            return Math.max(1024, SynapseConfig.MAX_BODY_BYTES.get());
        } catch (Throwable t) {
            return MAX_BODY_FALLBACK;
        }
    }

    /** Parses the raw query string into a decoded key→value map (last value wins). */
    public static Map<String, String> queryParams(HttpExchange exchange) {
        Map<String, String> params = new HashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return params;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                params.put(decode(pair), "");
            } else {
                params.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            }
        }
        return params;
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    /**
     * Parses the request body as a JSON object. Returns an empty object if the body is blank.
     *
     * <p>Requires {@code Content-Type: application/json}. application/json is NOT a CORS
     * "simple" content type, so a cross-origin browser must send a preflight — which Synapse
     * never answers with CORS headers — making these mutating endpoints unreachable from a
     * drive-by web page even before the local-origin guard.
     */
    public static JsonObject parseJsonBody(HttpExchange exchange) throws IOException, SynapseException {
        String ct = exchange.getRequestHeaders().getFirst("Content-Type");
        if (ct == null || !ct.trim().toLowerCase(Locale.ROOT).startsWith("application/json")) {
            throw new SynapseException(SynapseError.BAD_REQUEST,
                    "This endpoint requires Content-Type: application/json.")
                    .detail("hint", "Send the body as JSON with header 'Content-Type: application/json'.");
        }
        String body = readBody(exchange);
        if (body == null || body.isBlank()) {
            return new JsonObject();
        }
        try {
            JsonElement el = ResponseEnvelope.GSON.fromJson(body, JsonElement.class);
            if (el == null || !el.isJsonObject()) {
                throw new SynapseException(SynapseError.BAD_REQUEST, "Request body must be a JSON object.");
            }
            return el.getAsJsonObject();
        } catch (com.google.gson.JsonSyntaxException e) {
            throw new SynapseException(SynapseError.BAD_REQUEST, "Malformed JSON body: " + e.getMessage());
        }
    }

    public static String str(JsonObject o, String key, String def) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : def;
    }

    public static int intval(JsonObject o, String key, int def) throws SynapseException {
        if (!o.has(key) || o.get(key).isJsonNull()) {
            return def;
        }
        JsonElement e = o.get(key);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new SynapseException(SynapseError.BAD_REQUEST, "Field '" + key + "' must be a number.");
        }
        return e.getAsInt();
    }

    public static double dbl(JsonObject o, String key, double def) throws SynapseException {
        if (!o.has(key) || o.get(key).isJsonNull()) {
            return def;
        }
        JsonElement e = o.get(key);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new SynapseException(SynapseError.BAD_REQUEST, "Field '" + key + "' must be a number.");
        }
        return e.getAsDouble();
    }

    public static boolean bool(JsonObject o, String key, boolean def) throws SynapseException {
        if (!o.has(key) || o.get(key).isJsonNull()) {
            return def;
        }
        JsonElement e = o.get(key);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
            throw new SynapseException(SynapseError.BAD_REQUEST, "Field '" + key + "' must be a boolean.");
        }
        return e.getAsBoolean();
    }

    /** Writes a JSON response with the given status and closes the exchange. */
    public static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Writes a raw binary response (e.g. image/png) with the given status. */
    public static void sendRaw(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
