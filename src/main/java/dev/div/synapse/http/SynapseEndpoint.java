package dev.div.synapse.http;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

/**
 * One HTTP endpoint. Implementations return the {@code data} payload on success
 * and throw {@link SynapseException} to produce an error envelope. Each endpoint
 * also describes itself for {@code /manifest} so the API stays self-documenting
 * (spec §5.1).
 */
public interface SynapseEndpoint {

    /** Request path, e.g. {@code "/state"}. */
    String path();

    /** Accepted HTTP methods, e.g. {@code {"GET"}} or {@code {"GET","POST"}}. */
    String[] methods();

    /**
     * Produces the endpoint's result (JSON payload or raw binary).
     *
     * @throws SynapseException to emit a structured error response
     * @throws Exception        any other failure (wrapped as {@code INTERNAL})
     */
    EndpointResult handle(HttpExchange exchange) throws Exception;

    /** This endpoint's self-description fragment, embedded in {@code /manifest}. */
    JsonObject manifestFragment();
}
