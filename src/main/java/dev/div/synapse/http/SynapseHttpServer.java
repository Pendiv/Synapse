package dev.div.synapse.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.div.synapse.Synapse;
import dev.div.synapse.config.SynapseConfig;
import dev.div.synapse.core.ContextCollector;
import dev.div.synapse.core.LogCapture;
import dev.div.synapse.http.handlers.CommandHandler;
import dev.div.synapse.http.handlers.ManifestHandler;
import dev.div.synapse.http.handlers.ScreenshotHandler;
import dev.div.synapse.http.handlers.StateHandler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The always-on local HTTP server (spec §3). Runs on its own daemon thread pool;
 * each request is authenticated, method-checked, dispatched to a
 * {@link SynapseEndpoint}, and wrapped in the common envelope (§4).
 */
public final class SynapseHttpServer {

    public static final String AUTH_HEADER = "X-Synapse-Token";

    /** Post-handler context/log collection gets a small budget so it can never
     *  itself consume a full timeout (which would starve the small pool). */
    private static final long CONTEXT_BUDGET_MS = 500L;

    private static HttpServer server;
    private static ExecutorService executor;
    /** Published immutably and swapped atomically; readers iterate a stable snapshot. */
    private static volatile Map<String, SynapseEndpoint> endpoints = Collections.emptyMap();
    private static Thread shutdownHook;

    private SynapseHttpServer() {
    }

    /** Endpoints in declaration order (used by /manifest and tests). */
    public static List<SynapseEndpoint> endpoints() {
        return new ArrayList<>(endpoints.values());
    }

    public static synchronized void start() {
        if (server != null) {
            return;
        }

        // Build the endpoint set. /manifest is given the others so it can describe them.
        List<SynapseEndpoint> functional = new ArrayList<>();
        functional.add(new StateHandler());
        functional.add(new CommandHandler());
        if (SynapseConfig.SCREENSHOT_ENABLED.get()) {
            functional.add(new ScreenshotHandler());
        }
        ManifestHandler manifest = new ManifestHandler(functional);

        Map<String, SynapseEndpoint> map = new LinkedHashMap<>();
        map.put(manifest.path(), manifest);
        for (SynapseEndpoint e : functional) {
            map.put(e.path(), e);
        }
        Map<String, SynapseEndpoint> published = Collections.unmodifiableMap(map);

        String bind = SynapseConfig.BIND_ADDRESS.get();
        int port = SynapseConfig.PORT.get();
        try {
            server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        } catch (IOException e) {
            Synapse.LOGGER.error("[Synapse] Could not bind HTTP server to {}:{} — {}", bind, port, e.toString());
            server = null;
            return;
        }

        endpoints = published;
        for (SynapseEndpoint e : published.values()) {
            server.createContext(e.path(), exchange -> dispatch(e, exchange));
        }
        // Catch-all for unknown paths (longest-prefix match means specific paths win).
        server.createContext("/", SynapseHttpServer::handleUnknown);

        executor = Executors.newFixedThreadPool(2, daemonFactory());
        server.setExecutor(executor);
        server.start();

        shutdownHook = new Thread(SynapseHttpServer::stop, "synapse-http-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        if (SynapseConfig.AUTH_TOKEN.get().isEmpty()) {
            Synapse.LOGGER.warn("[Synapse] authToken is empty — auth is DISABLED. Anyone who can reach "
                    + "{}:{} can run arbitrary commands. Keep bindAddress on 127.0.0.1.", bind, port);
        }
        Synapse.LOGGER.info("[Synapse] HTTP bridge listening on http://{}:{} ({} endpoints). Try GET /manifest.",
                bind, port, published.size());
    }

    public static synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        endpoints = Collections.emptyMap();
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM already shutting down.
            }
            shutdownHook = null;
        }
    }

    // === request dispatch ===

    private static void dispatch(SynapseEndpoint endpoint, HttpExchange exchange) {
        String path = endpoint.path();
        boolean ok = false;
        int status = 200;
        JsonElement data = null;
        JsonObject error = null;
        SynapseError errorCode = null;
        EndpointResult raw = null;

        try {
            // createContext matches by prefix; require an exact path so /statex != /state.
            if (!exchange.getRequestURI().getPath().equals(path)) {
                handleUnknown(exchange);
                return;
            }
            requireAuth(exchange);
            requireMethod(exchange, endpoint.httpMethod());

            EndpointResult result = endpoint.handle(exchange);
            if (result.isRaw()) {
                raw = result;
            } else {
                data = result.json;
            }
            ok = true;
        } catch (SynapseException e) {
            error = e.toJson();
            status = e.httpStatus();
            errorCode = e.error;
        } catch (Throwable t) {
            error = new SynapseException(SynapseError.INTERNAL,
                    t.getClass().getSimpleName() + ": " + t.getMessage()).toJson();
            status = SynapseError.INTERNAL.httpStatus;
            errorCode = SynapseError.INTERNAL;
            Synapse.LOGGER.error("[Synapse] Unhandled error handling {}", path, t);
        }

        try {
            if (raw != null) {
                // Raw binary mode bypasses the envelope (e.g. image/png).
                HttpUtil.sendRaw(exchange, 200, raw.rawContentType, raw.rawBody);
            } else {
                String body = ResponseEnvelope.render(path, ok, data, error,
                        logsFor(errorCode), contextFor(errorCode), System.currentTimeMillis());
                HttpUtil.sendJson(exchange, status, body);
            }
        } catch (IOException io) {
            Synapse.LOGGER.error("[Synapse] Failed to write response for {}", path, io);
        } finally {
            exchange.close();
        }
    }

    private static void handleUnknown(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        int status;
        JsonObject error;
        SynapseError errorCode;
        try {
            // Auth still applies so an unauthenticated probe cannot enumerate freely.
            requireAuth(exchange);
            List<String> known = new ArrayList<>(endpoints.keySet());
            Collections.sort(known);
            SynapseException e = new SynapseException(SynapseError.BAD_REQUEST,
                    "Unknown endpoint: " + path + ". Call GET /manifest for the full API.")
                    .suggestions(known);
            error = e.toJson();
            status = e.httpStatus();
            errorCode = SynapseError.BAD_REQUEST;
        } catch (SynapseException auth) {
            error = auth.toJson();
            status = auth.httpStatus();
            errorCode = auth.error;
        }
        try {
            String body = ResponseEnvelope.render(path, false, null, error,
                    logsFor(errorCode), contextFor(errorCode), System.currentTimeMillis());
            HttpUtil.sendJson(exchange, status, body);
        } catch (IOException io) {
            Synapse.LOGGER.error("[Synapse] Failed to write error response", io);
        } finally {
            exchange.close();
        }
    }

    /** Logs are withheld from unauthenticated responses (no info leak). */
    private static JsonArray logsFor(SynapseError errorCode) {
        return errorCode == SynapseError.UNAUTHORIZED ? new JsonArray() : LogCapture.recentAsJson();
    }

    /**
     * Context for the envelope. Unauthenticated responses get an empty object —
     * this both avoids leaking game state and avoids scheduling main-thread work
     * for an unauthenticated caller. Otherwise collected with a small budget.
     */
    private static JsonObject contextFor(SynapseError errorCode) {
        if (errorCode == SynapseError.UNAUTHORIZED) {
            return new JsonObject();
        }
        long budget = Math.min(SynapseConfig.TIMEOUT_MS.get(), CONTEXT_BUDGET_MS);
        return ContextCollector.collectSafe(budget);
    }

    // === guards ===

    private static void requireAuth(HttpExchange exchange) throws SynapseException {
        String token = SynapseConfig.AUTH_TOKEN.get();
        if (token == null || token.isEmpty()) {
            return; // auth disabled
        }
        String provided = exchange.getRequestHeaders().getFirst(AUTH_HEADER);
        if (provided == null || !constantTimeEquals(provided, token)) {
            throw new SynapseException(SynapseError.UNAUTHORIZED,
                    "Missing or invalid " + AUTH_HEADER + " header.")
                    .detail("header", AUTH_HEADER);
        }
    }

    private static void requireMethod(HttpExchange exchange, String method) throws SynapseException {
        if (!exchange.getRequestMethod().equalsIgnoreCase(method)) {
            throw new SynapseException(SynapseError.BAD_REQUEST,
                    "This endpoint requires HTTP " + method + " but got "
                            + exchange.getRequestMethod() + ".")
                    .detail("expectedMethod", method);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static ThreadFactory daemonFactory() {
        AtomicInteger n = new AtomicInteger(1);
        return r -> {
            Thread t = new Thread(r, "synapse-http-" + n.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }
}
