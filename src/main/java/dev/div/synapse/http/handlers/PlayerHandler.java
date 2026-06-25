package dev.div.synapse.http.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dev.div.synapse.config.SynapseConfig;
import dev.div.synapse.core.PlayerController;
import dev.div.synapse.http.EndpointResult;
import dev.div.synapse.http.HttpUtil;
import dev.div.synapse.http.SynapseEndpoint;

/** {@code POST /player} — physical control: look, move, use, attack, hotbar. */
public final class PlayerHandler implements SynapseEndpoint {

    @Override
    public String path() {
        return "/player";
    }

    @Override
    public String[] methods() {
        return new String[]{"POST"};
    }

    @Override
    public EndpointResult handle(HttpExchange exchange) throws Exception {
        JsonObject body = HttpUtil.parseJsonBody(exchange);
        return EndpointResult.json(PlayerController.act(body, SynapseConfig.TIMEOUT_MS.get()));
    }

    @Override
    public JsonObject manifestFragment() {
        JsonObject f = new JsonObject();
        f.addProperty("path", "/player");
        f.addProperty("method", "POST");
        f.addProperty("desc", "Physically control the player. Interactions target whatever the player is looking at "
                + "(state.lookingAt / mc.hitResult).");
        JsonArray actions = new JsonArray();
        actions.add("{action:look, yaw:F, pitch:F}  (absolute) or {action:look, dyaw:F, dpitch:F}  (relative)");
        actions.add("{action:move, forward|back|left|right|jump|sneak:true, ticks:N}  (timed walk, N<=100)");
        actions.add("{action:use}   right-click (use item / use on block or entity)");
        actions.add("{action:attack}  left-click (attack entity / start breaking block)");
        actions.add("{action:selectHotbar, slot:0..8}");
        f.add("postActions", actions);
        f.addProperty("returns", "The action result (new yaw/pitch, elapsed ticks + pos, interaction result).");
        return f;
    }
}
