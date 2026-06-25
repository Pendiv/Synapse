package dev.div.synapse.http;

/**
 * Stable error codes returned to the AI client, each mapped to an HTTP status.
 * See spec §4.1. The wire {@code code} is the enum constant name.
 */
public enum SynapseError {
    NOT_IN_WORLD(409, "Player or integrated server not available (out of world / on a menu screen)."),
    UNAUTHORIZED(401, "Auth token missing or mismatched."),
    FORBIDDEN(403, "Request rejected by the local-origin guard (disallowed Host/Origin, or a cross-site browser request)."),
    BAD_REQUEST(400, "Malformed request (bad params, body, method, or unknown endpoint)."),
    COMMAND_FAILED(500, "A command threw or otherwise failed during execution."),
    TIMEOUT(504, "Main-thread work did not complete within the configured timeout."),
    SCREENSHOT_FAILED(500, "Framebuffer capture or PNG encode failed."),
    INTERNAL(500, "Unexpected internal exception.");

    public final int httpStatus;
    public final String description;

    SynapseError(int httpStatus, String description) {
        this.httpStatus = httpStatus;
        this.description = description;
    }

    /** The string emitted as {@code error.code} on the wire. */
    public String code() {
        return name();
    }
}
