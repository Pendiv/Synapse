package dev.div.synapse.http.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dev.div.synapse.config.SynapseConfig;
import dev.div.synapse.core.ChatCapture;
import dev.div.synapse.core.MainThread;
import dev.div.synapse.http.EndpointResult;
import dev.div.synapse.http.HttpUtil;
import dev.div.synapse.http.SynapseEndpoint;
import dev.div.synapse.http.SynapseError;
import dev.div.synapse.http.SynapseException;

/** {@code GET /chat} reads recent received chat; {@code POST /chat} sends chat/command. */
public final class ChatHandler implements SynapseEndpoint {

    @Override
    public String path() {
        return "/chat";
    }

    @Override
    public String[] methods() {
        return new String[]{"GET", "POST"};
    }

    @Override
    public EndpointResult handle(HttpExchange exchange) throws Exception {
        if (exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            JsonArray messages = ChatCapture.recentAsJson();
            JsonObject data = new JsonObject();
            data.add("messages", messages);
            data.addProperty("count", messages.size());
            return EndpointResult.json(data);
        }
        JsonObject body = HttpUtil.parseJsonBody(exchange);
        String text = HttpUtil.str(body, "text", "");
        if (text.isBlank()) {
            throw new SynapseException(SynapseError.BAD_REQUEST, "POST body needs a non-empty 'text'.");
        }
        long timeout = SynapseConfig.TIMEOUT_MS.get();
        MainThread.run(() -> {
            ChatCapture.send(text);
            return null;
        }, timeout);
        JsonObject data = new JsonObject();
        data.addProperty("sent", text);
        return EndpointResult.json(data);
    }

    @Override
    public JsonObject manifestFragment() {
        JsonObject f = new JsonObject();
        f.addProperty("path", "/chat");
        f.addProperty("method", "GET | POST");
        f.addProperty("desc", "GET: recent received chat lines. POST {text}: send chat (or a command if text "
                + "starts with '/'). Mod feedback often goes to chat — read it here.");
        f.addProperty("returns", "GET: { messages[], count }. POST: { sent }.");
        return f;
    }
}
