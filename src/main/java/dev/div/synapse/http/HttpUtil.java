package dev.div.synapse.http;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Small JDK-HttpServer helpers: body reading, query parsing, response writing. */
public final class HttpUtil {

    private HttpUtil() {
    }

    /** Reads the full request body as UTF-8. Returns "" if there is none. */
    public static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
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

    /** Parses the request body as a JSON object. Returns an empty object if the body is blank. */
    public static JsonObject parseJsonBody(HttpExchange exchange) throws IOException, SynapseException {
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
