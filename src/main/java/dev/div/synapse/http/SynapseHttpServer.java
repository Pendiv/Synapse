package dev.div.synapse.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.div.synapse.Synapse;
import dev.div.synapse.config.AccessLevel;
import dev.div.synapse.config.AuthToken;
import dev.div.synapse.config.InstanceRegistry;
import dev.div.synapse.config.SynapseConfig;
import dev.div.synapse.core.ContextCollector;
import dev.div.synapse.core.LogCapture;
import dev.div.synapse.http.handlers.BatchHandler;
import dev.div.synapse.http.handlers.ChatHandler;
import dev.div.synapse.http.handlers.CommandHandler;
import dev.div.synapse.http.handlers.GuiHandler;
import dev.div.synapse.http.handlers.ManifestHandler;
import dev.div.synapse.http.handlers.PlayerHandler;
import dev.div.synapse.http.handlers.ScreenshotHandler;
import dev.div.synapse.http.handlers.StateHandler;
import dev.div.synapse.http.handlers.WaitHandler;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
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

    static {
        // Bound slow/stalled REQUESTS (slowloris): if the request headers+body aren't fully received
        // within maxReqTime seconds, the JDK server closes the socket and frees the pool thread.
        // Read once when sun.net.httpserver.ServerConfig is class-loaded (first HttpServer creation
        // in the JVM), so set before any server is created.
        //
        // We deliberately do NOT set maxRspTime: it bounds the handler+response phase, which would
        // abort legitimately long-blocking endpoints (/wait, or a /cmd against a paused game up to
        // the configured main-thread timeout). The request-side cap is what defeats the slow-body
        // attack; a slow response READER is a minor, local-only residual.
        setIfAbsent("sun.net.httpserver.maxReqTime", "30");
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }

    /** How many sequential ports to try when autoPort is on and the first is busy. */
    private static final int PORT_SCAN_SPAN = 20;

    /** A per-launch identity so a consumer can tell this live instance from a crash-stale entry. */
    private static final String INSTANCE_ID = java.util.UUID.randomUUID().toString();

    private static HttpServer server;
    private static ExecutorService executor;
    /** The port actually bound (may differ from config when autoPort steps past a busy port). */
    private static volatile int actualPort = -1;
    /** Published immutably and swapped atomically; readers iterate a stable snapshot. */
    private static volatile Map<String, SynapseEndpoint> endpoints = Collections.emptyMap();
    private static Thread shutdownHook;

    private SynapseHttpServer() {
    }

    /** The port the bridge is actually listening on (falls back to the configured port if not started). */
    public static int port() {
        return actualPort > 0 ? actualPort : SynapseConfig.PORT.get();
    }

    /** This launch's unique instance id (also surfaced in /manifest's instance block). */
    public static String instanceId() {
        return INSTANCE_ID;
    }

    /** Endpoints in declaration order (used by /manifest and tests). */
    public static List<SynapseEndpoint> endpoints() {
        return new ArrayList<>(endpoints.values());
    }

    public static synchronized void start() {
        if (server != null) {
            return;
        }

        AuthToken.resolve();

        String bind = SynapseConfig.BIND_ADDRESS.get();
        // Interlock: never expose an unauthenticated bridge to the network. Auth is on by default
        // (auto-generated token), so this only trips if a user both binds off-loopback AND forces
        // auth off — in which case running level-4 /cmd for the whole LAN is refused outright.
        if (!isLoopback(bind) && !AuthToken.enabled()) {
            Synapse.LOGGER.error("[Synapse] REFUSING to start: bindAddress '{}' is not loopback and no auth token "
                    + "is set. That would expose level-4 command execution to the network. Set an authToken or "
                    + "bind to 127.0.0.1.", bind);
            return;
        }

        // Build the endpoint set. /manifest is given the others so it can describe them.
        List<SynapseEndpoint> functional = new ArrayList<>();
        functional.add(new StateHandler());
        functional.add(new CommandHandler());
        functional.add(new GuiHandler());
        functional.add(new PlayerHandler());
        functional.add(new WaitHandler());
        functional.add(new ChatHandler());
        functional.add(new BatchHandler());
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

        int startPort = SynapseConfig.PORT.get();
        int span = SynapseConfig.AUTO_PORT.get() ? PORT_SCAN_SPAN : 1;
        int scanMax = Math.min(startPort + span - 1, 65535);
        int port = -1;
        for (int p = startPort; p <= scanMax; p++) {
            try {
                server = HttpServer.create(new InetSocketAddress(bind, p), 0);
                port = p;
                break;
            } catch (java.net.BindException busy) {
                server = null;
                if (span == 1) {
                    Synapse.LOGGER.error("[Synapse] Could not bind HTTP server to {}:{} — {}", bind, p, busy.toString());
                    return;
                }
                // autoPort: this port is taken (likely another instance) — try the next one.
            } catch (IOException e) {
                // A non-"address in use" failure won't be fixed by trying another port.
                Synapse.LOGGER.error("[Synapse] Could not create HTTP server on {}:{} — {}", bind, p, e.toString());
                server = null;
                return;
            }
        }
        if (server == null) {
            Synapse.LOGGER.error("[Synapse] Could not bind HTTP server to {} on any port in {}..{}.",
                    bind, startPort, scanMax);
            return;
        }
        actualPort = port;

        endpoints = published;
        for (SynapseEndpoint e : published.values()) {
            server.createContext(e.path(), exchange -> dispatch(e, exchange));
        }
        // Catch-all for unknown paths (longest-prefix match means specific paths win).
        server.createContext("/", SynapseHttpServer::handleUnknown);

        // A few threads so blocking endpoints (/wait, /player move) can't starve
        // the observation endpoints for a sequential-but-occasionally-overlapping AI.
        executor = Executors.newFixedThreadPool(6, daemonFactory());
        server.setExecutor(executor);
        server.start();

        shutdownHook = new Thread(SynapseHttpServer::stop, "synapse-http-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        AccessLevel ceiling = AuthToken.ceiling();
        if (AuthToken.source() == AuthToken.Source.CONFIG) {
            Synapse.LOGGER.info("[Synapse] Auth ENABLED (single token from config). Access ceiling: {}.", ceiling.id());
        } else {
            Synapse.LOGGER.info("[Synapse] Auth ENABLED. Per-level tokens in {}; the ceiling-level token is mirrored "
                    + "to {} for the MCP. Access ceiling: {}. Tokens are never logged.",
                    AuthToken.tokensFile().toAbsolutePath(), AuthToken.tokenFile().toAbsolutePath(), ceiling.id());
        }
        if (ceiling == AccessLevel.DEVELOPER) {
            Synapse.LOGGER.warn("[Synapse] accessLevel ceiling is DEVELOPER — an agent with the developer token can run "
                    + "arbitrary level-{} commands. At your own risk.", SynapseConfig.COMMAND_PERMISSION_LEVEL.get());
        }
        String baseUrl = "http://" + bind + ":" + port;
        InstanceRegistry.register(INSTANCE_ID, port, baseUrl, AuthToken.effectiveToken(),
                ManifestHandler.MC_VERSION, System.currentTimeMillis());

        if (port != startPort) {
            Synapse.LOGGER.info("[Synapse] Port {} was busy; bound {} instead (autoPort).", startPort, port);
        }
        Synapse.LOGGER.info("[Synapse] HTTP bridge '{}' listening on {} ({} endpoints). Try GET /manifest.",
                InstanceRegistry.instanceName(), baseUrl, published.size());
    }

    public static synchronized void stop() {
        if (server != null) {
            InstanceRegistry.unregister();
            server.stop(0);
            server = null;
        }
        actualPort = -1;
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
            requireLocalRequest(exchange);
            AccessControl.setCurrent(requireAuth(exchange));
            requireMethod(exchange, endpoint.methods());

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
            AccessControl.clearCurrent();
            exchange.close();
        }
    }

    private static void handleUnknown(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        int status;
        JsonObject error;
        SynapseError errorCode;
        try {
            // The local-origin guard and auth still apply so a browser/unauthenticated probe
            // cannot enumerate freely.
            requireLocalRequest(exchange);
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
            AccessControl.clearCurrent();
            exchange.close();
        }
    }

    /** Logs are withheld from rejected (unauthenticated / blocked-origin) responses (no info leak). */
    private static JsonArray logsFor(SynapseError errorCode) {
        return withheld(errorCode) ? new JsonArray() : LogCapture.recentAsJson();
    }

    /**
     * Context for the envelope. Rejected responses (unauthorized / forbidden) get an empty
     * object — this both avoids leaking game state and avoids work for a caller we already
     * turned away. Otherwise it's a per-tick snapshot (no main-thread hop).
     */
    private static JsonObject contextFor(SynapseError errorCode) {
        if (withheld(errorCode)) {
            return new JsonObject();
        }
        return ContextCollector.snapshot();
    }

    private static boolean withheld(SynapseError errorCode) {
        return errorCode == SynapseError.UNAUTHORIZED || errorCode == SynapseError.FORBIDDEN;
    }

    // === guards ===

    /**
     * Rejects requests that look like they came from a web browser pointed at the loopback
     * bridge (CSRF / DNS-rebinding). Only enforced on a loopback bind — a deliberate LAN bind
     * is an opt-in protected by the auth token, and the rebinding threat is loopback-specific.
     *
     * <ul>
     *   <li><b>Host</b> must name a loopback host on our port — defeats DNS rebinding, where the
     *       browser carries the attacker's hostname in Host.</li>
     *   <li><b>Sec-Fetch-Site</b> of {@code cross-site}/{@code same-site} is rejected — catches
     *       browser-originated requests even when Origin is absent.</li>
     *   <li><b>Origin</b>, if present, must be a localhost origin — non-browser agents send none.</li>
     * </ul>
     */
    private static void requireLocalRequest(HttpExchange exchange) throws SynapseException {
        if (!isLoopback(SynapseConfig.BIND_ADDRESS.get())) {
            return;
        }
        Headers h = exchange.getRequestHeaders();
        // Validate against the port we ACTUALLY bound (autoPort may have stepped past the
        // configured one) — the client's Host header carries the real port it connected to.
        int port = port();

        String host = h.getFirst("Host");
        if (!RequestGuards.hostAllowed(host, port)) {
            throw new SynapseException(SynapseError.FORBIDDEN,
                    "Host header '" + host + "' is not an allowed loopback host (blocks DNS-rebinding).")
                    .detail("hint", "Reach Synapse via http://127.0.0.1:" + port + " or http://localhost:" + port + ".");
        }

        String secSite = h.getFirst("Sec-Fetch-Site");
        if (RequestGuards.isBlockedSecFetchSite(secSite)) {
            throw new SynapseException(SynapseError.FORBIDDEN,
                    "Cross-origin browser request blocked (Sec-Fetch-Site: " + secSite + ").")
                    .detail("hint", "Synapse is a local API for non-browser agents; web pages cannot drive it.");
        }

        String origin = h.getFirst("Origin");
        if (origin != null && !origin.isEmpty() && !RequestGuards.originAllowed(origin, port)) {
            throw new SynapseException(SynapseError.FORBIDDEN,
                    "Origin '" + origin + "' is not allowed.")
                    .detail("hint", "Only a non-browser local agent (no Origin) or a localhost origin is accepted.");
        }
    }

    private static boolean isLoopback(String bind) {
        try {
            return InetAddress.getByName(bind).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }

    /** Authenticates and returns the {@link AccessLevel} granted to this request. */
    private static AccessLevel requireAuth(HttpExchange exchange) throws SynapseException {
        String provided = exchange.getRequestHeaders().getFirst(AUTH_HEADER);
        AccessLevel tokenLevel = AuthToken.levelForToken(provided);
        if (tokenLevel == null) {
            throw new SynapseException(SynapseError.UNAUTHORIZED,
                    "Missing or invalid " + AUTH_HEADER + " header.")
                    .detail("header", AUTH_HEADER);
        }
        // The token's level, lowered to the configured ceiling (developer stays opt-in).
        return tokenLevel.cappedAt(AuthToken.ceiling());
    }

    private static void requireMethod(HttpExchange exchange, String[] methods) throws SynapseException {
        String actual = exchange.getRequestMethod();
        for (String allowed : methods) {
            if (allowed.equalsIgnoreCase(actual)) {
                return;
            }
        }
        throw new SynapseException(SynapseError.BAD_REQUEST,
                "This endpoint requires HTTP " + String.join("/", methods) + " but got " + actual + ".")
                .detail("expectedMethods", String.join(",", methods));
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
