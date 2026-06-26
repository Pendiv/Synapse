package dev.div.synapse.http.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dev.div.synapse.config.SynapseConfig;
import dev.div.synapse.core.GuiController;
import dev.div.synapse.http.AccessControl;
import dev.div.synapse.http.EndpointResult;
import dev.div.synapse.http.HttpUtil;
import dev.div.synapse.http.SynapseEndpoint;

/**
 * {@code GET /gui} observes the open screen (class, title, widgets, container
 * slots). {@code POST /gui} drives it: open/close/clickSlot/clickButton/type.
 */
public final class GuiHandler implements SynapseEndpoint {

    @Override
    public String path() {
        return "/gui";
    }

    @Override
    public String[] methods() {
        return new String[]{"GET", "POST"};
    }

    @Override
    public EndpointResult handle(HttpExchange exchange) throws Exception {
        long timeout = SynapseConfig.TIMEOUT_MS.get();
        if (exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            return EndpointResult.json(GuiController.observe(timeout));
        }
        JsonObject body = HttpUtil.parseJsonBody(exchange);
        AccessControl.require(AccessControl.levelForGui(body));
        return EndpointResult.json(GuiController.act(body, timeout));
    }

    @Override
    public JsonObject manifestFragment() {
        JsonObject f = new JsonObject();
        f.addProperty("path", "/gui");
        f.addProperty("method", "GET | POST");
        f.addProperty("requires", "GET: observe; POST: play (developer to open the creative inventory)");
        f.addProperty("desc", "GET: read the open Screen (class, title, widgets[{index,type,label,x,y,active,value?}], "
                + "container{slots,carried}). POST: act on it.");
        JsonArray actions = new JsonArray();
        actions.add("{action:open, target:inventory|creative}");
        actions.add("{action:close}");
        actions.add("{action:clickSlot, slot:N, button:0|1, mode:PICKUP|QUICK_MOVE|SWAP|CLONE|THROW|PICKUP_ALL}");
        actions.add("{action:clickButton, index:N | label:\"text\"}");
        actions.add("{action:type, index:N | label:\"text\", text:\"...\", replace:true}");
        f.add("postActions", actions);
        f.addProperty("returns", "GET: the screen snapshot (widgets[], widgetCount). POST: the action result. "
                + "Widget 'index' can shift if the screen changes between GET and POST — prefer 'label', "
                + "or re-GET /gui just before acting. Read back with GET /gui.");
        return f;
    }
}
