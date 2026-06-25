package dev.div.synapse.http.handlers;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dev.div.synapse.config.SynapseConfig;
import dev.div.synapse.core.MainThread;
import dev.div.synapse.core.StateCollector;
import dev.div.synapse.http.EndpointResult;
import dev.div.synapse.http.HttpUtil;
import dev.div.synapse.http.SynapseEndpoint;
import dev.div.synapse.http.SynapseError;
import dev.div.synapse.http.SynapseException;

import java.util.Map;

/** {@code GET /state?mode=summary|full} — ground-truth logical state (spec §5.2). */
public final class StateHandler implements SynapseEndpoint {

    @Override
    public String path() {
        return "/state";
    }

    @Override
    public String httpMethod() {
        return "GET";
    }

    @Override
    public EndpointResult handle(HttpExchange exchange) throws Exception {
        Map<String, String> q = HttpUtil.queryParams(exchange);
        String mode = q.getOrDefault("mode", "summary");
        long timeout = SynapseConfig.TIMEOUT_MS.get();

        JsonObject data;
        if (mode.equalsIgnoreCase("summary")) {
            data = MainThread.run(StateCollector::summary, timeout);
        } else if (mode.equalsIgnoreCase("full")) {
            data = MainThread.run(StateCollector::full, timeout);
        } else {
            throw new SynapseException(SynapseError.BAD_REQUEST,
                    "mode must be 'summary' or 'full', got '" + mode + "'.");
        }
        return EndpointResult.json(data);
    }

    @Override
    public JsonObject manifestFragment() {
        JsonObject f = new JsonObject();
        f.addProperty("path", "/state");
        f.addProperty("method", "GET");
        f.addProperty("desc", "Current ground-truth game state. Primary observation endpoint.");
        JsonObject params = new JsonObject();
        params.addProperty("mode", "summary | full (default summary)");
        f.add("params", params);
        f.addProperty("returns", "See stateSchema. NOT_IN_WORLD on menu screens.");
        return f;
    }
}
