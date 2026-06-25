package dev.div.synapse.http.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dev.div.synapse.config.SynapseConfig;
import dev.div.synapse.core.CommandRunner;
import dev.div.synapse.core.MainThread;
import dev.div.synapse.core.StateCollector;
import dev.div.synapse.http.EndpointResult;
import dev.div.synapse.http.HttpUtil;
import dev.div.synapse.http.SynapseEndpoint;
import dev.div.synapse.http.SynapseError;
import dev.div.synapse.http.SynapseException;

/** {@code POST /cmd} — run a Minecraft command at permission level 4 (spec §5.3). */
public final class CommandHandler implements SynapseEndpoint {

    @Override
    public String path() {
        return "/cmd";
    }

    @Override
    public String httpMethod() {
        return "POST";
    }

    @Override
    public EndpointResult handle(HttpExchange exchange) throws Exception {
        String body = HttpUtil.readBody(exchange);
        if (body == null || body.strip().isEmpty()) {
            throw new SynapseException(SynapseError.BAD_REQUEST,
                    "POST body must contain the command text (leading slash optional).");
        }
        long timeout = SynapseConfig.TIMEOUT_MS.get();

        JsonObject data = CommandRunner.run(body, timeout);

        // Best-effort post-execution snapshot. May lag the command by ~1 tick
        // (client state syncs from the server next tick); omitted if unavailable.
        try {
            data.add("stateAfter", MainThread.run(StateCollector::summary, timeout));
        } catch (SynapseException ignored) {
            // leave stateAfter out
        }
        return EndpointResult.json(data);
    }

    @Override
    public JsonObject manifestFragment() {
        JsonObject f = new JsonObject();
        f.addProperty("path", "/cmd");
        f.addProperty("method", "POST");
        f.addProperty("desc", "Execute a Minecraft command (leading slash optional). Runs at permission level 4.");
        f.addProperty("body", "The command string, e.g. 'give @s minecraft:diamond 64'.");
        f.addProperty("returns", "{ command, success, resultValue, feedback[], stateAfter? }. "
                + "Envelope ok=true if the command parsed and ran; data.success reflects the command's own result.");
        JsonArray examples = new JsonArray();
        examples.add("give @s minecraft:diamond 64");
        examples.add("tp @s 0 100 0");
        examples.add("time set day");
        f.add("examples", examples);
        return f;
    }
}
