package dev.div.synapse.http.handlers;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dev.div.synapse.core.TickClock;
import dev.div.synapse.http.EndpointResult;
import dev.div.synapse.http.HttpUtil;
import dev.div.synapse.http.SynapseEndpoint;
import dev.div.synapse.http.SynapseError;
import dev.div.synapse.http.SynapseException;

import java.util.Map;

/** {@code GET /wait?ticks=N} — block until N client ticks pass (synchronisation). */
public final class WaitHandler implements SynapseEndpoint {

    private static final int MAX_TICKS = 600; // ~30s cap

    @Override
    public String path() {
        return "/wait";
    }

    @Override
    public String[] methods() {
        return new String[]{"GET"};
    }

    @Override
    public EndpointResult handle(HttpExchange exchange) throws Exception {
        Map<String, String> q = HttpUtil.queryParams(exchange);
        int ticks;
        try {
            ticks = Integer.parseInt(q.getOrDefault("ticks", "1"));
        } catch (NumberFormatException e) {
            throw new SynapseException(SynapseError.BAD_REQUEST, "ticks must be an integer.");
        }
        if (ticks < 1 || ticks > MAX_TICKS) {
            throw new SynapseException(SynapseError.BAD_REQUEST, "ticks must be 1.." + MAX_TICKS + ".");
        }
        // Independent budget: ~60ms/tick + slack (a tick is 50ms when not lagging).
        long elapsed = TickClock.waitTicks(ticks, ticks * 60L + 1000L);
        JsonObject data = new JsonObject();
        data.addProperty("requestedTicks", ticks);
        data.addProperty("elapsedTicks", elapsed);
        return EndpointResult.json(data);
    }

    @Override
    public JsonObject manifestFragment() {
        JsonObject f = new JsonObject();
        f.addProperty("path", "/wait");
        f.addProperty("method", "GET");
        f.addProperty("desc", "Block until N client ticks elapse (20 ticks = 1s). Use to let an action settle "
                + "before observing. Returns TIMEOUT if the game is paused.");
        JsonObject params = new JsonObject();
        params.addProperty("ticks", "1.." + MAX_TICKS);
        f.add("params", params);
        f.addProperty("returns", "{ requestedTicks, elapsedTicks }");
        return f;
    }
}
