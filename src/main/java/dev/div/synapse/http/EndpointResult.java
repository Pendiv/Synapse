package dev.div.synapse.http;

import com.google.gson.JsonElement;

/**
 * What an endpoint produced: either a JSON {@code data} payload (wrapped in the
 * common envelope) or a raw binary body (sent as-is, e.g. {@code image/png} for
 * {@code /screenshot?format=raw}). Errors are signalled by throwing
 * {@link SynapseException}, not via this type.
 */
public final class EndpointResult {

    public final JsonElement json;
    public final String rawContentType;
    public final byte[] rawBody;

    private EndpointResult(JsonElement json, String rawContentType, byte[] rawBody) {
        this.json = json;
        this.rawContentType = rawContentType;
        this.rawBody = rawBody;
    }

    /** A JSON payload to be wrapped in the response envelope. */
    public static EndpointResult json(JsonElement data) {
        return new EndpointResult(data, null, null);
    }

    /** A raw binary body bypassing the envelope (only for explicit binary modes). */
    public static EndpointResult raw(String contentType, byte[] body) {
        return new EndpointResult(null, contentType, body);
    }

    public boolean isRaw() {
        return rawBody != null;
    }
}
