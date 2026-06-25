package dev.div.synapse.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

/**
 * Carries a {@link SynapseError} plus an AI-friendly message and optional
 * context fields (currentScreen, hint, suggestions, ...). Serialised into the
 * {@code error} object of the response envelope (spec §4.1).
 */
public class SynapseException extends Exception {

    public final SynapseError error;
    private final JsonObject extra = new JsonObject();

    public SynapseException(SynapseError error, String message) {
        super(message);
        this.error = error;
    }

    public SynapseException(SynapseError error, String message, Throwable cause) {
        super(message, cause);
        this.error = error;
    }

    /** Adds a string detail (e.g. {@code currentScreen}, {@code hint}). Chainable. */
    public SynapseException detail(String key, String value) {
        if (value != null) {
            extra.addProperty(key, value);
        }
        return this;
    }

    /** Sets the {@code suggestions} array — concrete next-step candidates for the AI. */
    public SynapseException suggestions(List<String> suggestions) {
        JsonArray arr = new JsonArray();
        if (suggestions != null) {
            for (String s : suggestions) {
                arr.add(s);
            }
        }
        extra.add("suggestions", arr);
        return this;
    }

    /** Builds the {@code error} JSON object (spec §4.1). Always includes a suggestions array. */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("code", error.code());
        o.addProperty("message", getMessage());
        for (Map.Entry<String, com.google.gson.JsonElement> e : extra.entrySet()) {
            o.add(e.getKey(), e.getValue());
        }
        if (!o.has("suggestions")) {
            o.add("suggestions", new JsonArray());
        }
        return o;
    }

    public int httpStatus() {
        return error.httpStatus;
    }
}
