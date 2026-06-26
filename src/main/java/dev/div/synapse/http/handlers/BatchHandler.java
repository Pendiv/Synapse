package dev.div.synapse.http.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dev.div.synapse.config.AccessLevel;
import dev.div.synapse.config.SynapseConfig;
import dev.div.synapse.core.ChatCapture;
import dev.div.synapse.core.CommandRunner;
import dev.div.synapse.core.GuiController;
import dev.div.synapse.core.MainThread;
import dev.div.synapse.core.PlayerController;
import dev.div.synapse.core.StateCollector;
import dev.div.synapse.core.TickClock;
import dev.div.synapse.http.AccessControl;
import dev.div.synapse.http.EndpointResult;
import dev.div.synapse.http.HttpUtil;
import dev.div.synapse.http.SynapseEndpoint;
import dev.div.synapse.http.SynapseError;
import dev.div.synapse.http.SynapseException;

import java.util.Locale;

/**
 * {@code POST /batch} — run a sequence of operations in one request, cutting the
 * observe→act→verify round-trips an AI otherwise makes one HTTP call at a time.
 *
 * <p>Body: <code>{ "ops": [ &lt;op&gt;, ... ], "stopOnError": false }</code>. Each op
 * delegates to the same core logic as its standalone endpoint, so semantics match
 * exactly. Ops run in order on the handler thread; a failing op is reported in-band
 * (and aborts the rest only when {@code stopOnError} is true).
 */
public final class BatchHandler implements SynapseEndpoint {

    private static final int MAX_OPS = 32;
    private static final int MAX_WAIT_TICKS = 600;

    @Override
    public String path() {
        return "/batch";
    }

    @Override
    public String[] methods() {
        return new String[]{"POST"};
    }

    @Override
    public EndpointResult handle(HttpExchange exchange) throws Exception {
        JsonObject body = HttpUtil.parseJsonBody(exchange);
        if (!body.has("ops") || !body.get("ops").isJsonArray()) {
            throw new SynapseException(SynapseError.BAD_REQUEST, "Body needs an 'ops' array.");
        }
        JsonArray ops = body.getAsJsonArray("ops");
        if (ops.isEmpty()) {
            throw new SynapseException(SynapseError.BAD_REQUEST, "'ops' is empty.");
        }
        if (ops.size() > MAX_OPS) {
            throw new SynapseException(SynapseError.BAD_REQUEST, "Too many ops (max " + MAX_OPS + ").");
        }
        boolean stopOnError = HttpUtil.bool(body, "stopOnError", false);
        long opTimeout = SynapseConfig.TIMEOUT_MS.get();
        // One wall-clock budget for the whole batch, so a request (e.g. several /wait ops) can't
        // pin a worker thread far longer than a single standalone call.
        long batchDeadline = System.currentTimeMillis() + SynapseConfig.BATCH_BUDGET_MS.get();

        JsonArray results = new JsonArray();
        boolean ranAll = true;
        for (int i = 0; i < ops.size(); i++) {
            JsonObject result = new JsonObject();
            result.addProperty("index", i);

            long remaining = batchDeadline - System.currentTimeMillis();
            if (remaining <= 0) {
                result.addProperty("ok", false);
                result.addProperty("skipped", true);
                result.add("error", new SynapseException(SynapseError.TIMEOUT,
                        "Batch wall-clock budget exhausted before this op ran.").toJson());
                results.add(result);
                ranAll = false;
                continue;
            }

            boolean failed;
            JsonElement el = ops.get(i);
            if (!el.isJsonObject()) {
                result.addProperty("ok", false);
                result.add("error", new SynapseException(SynapseError.BAD_REQUEST, "op must be a JSON object.").toJson());
                failed = true;
            } else {
                JsonObject op = el.getAsJsonObject();
                result.addProperty("op", HttpUtil.str(op, "op", ""));
                try {
                    result.add("data", runOp(op, opTimeout, remaining));
                    result.addProperty("ok", true);
                    failed = false;
                } catch (SynapseException se) {
                    result.addProperty("ok", false);
                    result.add("error", se.toJson());
                    failed = true;
                } catch (Exception e) {
                    result.addProperty("ok", false);
                    result.add("error", new SynapseException(SynapseError.INTERNAL,
                            e.getClass().getSimpleName() + ": " + e.getMessage()).toJson());
                    failed = true;
                }
            }
            results.add(result);
            if (failed && stopOnError) {
                ranAll = false;
                break;
            }
        }

        JsonObject data = new JsonObject();
        data.add("results", results);
        data.addProperty("count", results.size());
        data.addProperty("ranAll", ranAll);
        return EndpointResult.json(data);
    }

    /**
     * Dispatches one op to the same core call its standalone endpoint uses. {@code opTimeout} is
     * the per-op main-thread timeout; {@code remaining} is the batch's remaining wall-clock budget —
     * both main-thread work and a blocking wait are clamped to it so the batch can't overrun.
     */
    private static JsonObject runOp(JsonObject op, long opTimeout, long remaining) throws Exception {
        long timeout = Math.min(opTimeout, remaining);
        String name = HttpUtil.str(op, "op", "").trim().toLowerCase(Locale.ROOT);
        switch (name) {
            case "cmd": {
                AccessControl.require(AccessLevel.DEVELOPER);
                String command = HttpUtil.str(op, "command", "");
                if (command.isBlank()) {
                    throw new SynapseException(SynapseError.BAD_REQUEST, "cmd op needs a non-empty 'command'.");
                }
                return CommandRunner.run(command, timeout);
            }
            case "state": {
                String mode = HttpUtil.str(op, "mode", "summary");
                if (mode.equalsIgnoreCase("full")) {
                    return MainThread.run(StateCollector::full, timeout);
                }
                if (mode.equalsIgnoreCase("summary")) {
                    return MainThread.run(StateCollector::summary, timeout);
                }
                throw new SynapseException(SynapseError.BAD_REQUEST, "state mode must be 'summary' or 'full'.");
            }
            case "player":
                AccessControl.require(AccessLevel.PLAY);
                return PlayerController.act(op, timeout);
            case "gui":
                if (op.has("action")) {
                    AccessControl.require(AccessControl.levelForGui(op));
                    return GuiController.act(op, timeout);
                }
                return GuiController.observe(timeout);
            case "chat": {
                if (op.has("text") && !op.get("text").isJsonNull()) {
                    String text = HttpUtil.str(op, "text", "");
                    if (text.isBlank()) {
                        throw new SynapseException(SynapseError.BAD_REQUEST, "chat 'text' is empty.");
                    }
                    AccessControl.require(AccessControl.levelForChat(text));
                    if (AccessControl.isCommand(text)) {
                        return CommandRunner.run(text, timeout); // honours commandPermissionLevel
                    }
                    MainThread.run(() -> {
                        ChatCapture.send(text);
                        return null;
                    }, timeout);
                    JsonObject d = new JsonObject();
                    d.addProperty("sent", text);
                    return d;
                }
                JsonArray msgs = ChatCapture.recentAsJson();
                JsonObject d = new JsonObject();
                d.add("messages", msgs);
                d.addProperty("count", msgs.size());
                return d;
            }
            case "wait": {
                int ticks = HttpUtil.intval(op, "ticks", 1);
                if (ticks < 1 || ticks > MAX_WAIT_TICKS) {
                    throw new SynapseException(SynapseError.BAD_REQUEST, "wait 'ticks' must be 1.." + MAX_WAIT_TICKS + ".");
                }
                long budget = Math.min(ticks * 60L + 1000L, remaining); // never outlast the batch budget
                long elapsed = TickClock.waitTicks(ticks, budget);
                JsonObject d = new JsonObject();
                d.addProperty("requestedTicks", ticks);
                d.addProperty("elapsedTicks", elapsed);
                return d;
            }
            default:
                throw new SynapseException(SynapseError.BAD_REQUEST,
                        "Unknown op '" + name + "'. Use one of: cmd, state, player, gui, chat, wait.");
        }
    }

    @Override
    public JsonObject manifestFragment() {
        JsonObject f = new JsonObject();
        f.addProperty("path", "/batch");
        f.addProperty("method", "POST");
        f.addProperty("desc", "Run several operations in one request, in order — cuts observe/act/verify "
                + "round-trips. Body: { ops:[...], stopOnError?:false }. Each op is one of: "
                + "{op:'cmd', command} | {op:'state', mode} | {op:'player', action, ...} | "
                + "{op:'gui', action?, ...} | {op:'chat', text?} | {op:'wait', ticks}. Max " + MAX_OPS + " ops. "
                + "Each op obeys the same accessLevel as its endpoint (a disallowed op fails in-band).");
        JsonArray example = new JsonArray();
        example.add("{\"op\":\"cmd\",\"command\":\"give @s minecraft:diamond 64\"}");
        example.add("{\"op\":\"wait\",\"ticks\":2}");
        example.add("{\"op\":\"state\",\"mode\":\"summary\"}");
        f.add("exampleOps", example);
        f.addProperty("returns", "{ results:[{index, op, ok, data|error, skipped?}], count, ranAll }. "
                + "Each op's data is that endpoint's core payload (the cmd op omits the convenience "
                + "stateAfter — add an explicit {op:'state'} if you need it); failures are reported in-band, "
                + "and ops past the batch budget come back ok:false, skipped:true.");
        return f;
    }
}
