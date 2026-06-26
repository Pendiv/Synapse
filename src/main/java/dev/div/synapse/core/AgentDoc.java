package dev.div.synapse.core;

import dev.div.synapse.Synapse;
import dev.div.synapse.config.AuthToken;
import dev.div.synapse.config.SynapseConfig;
import dev.div.synapse.http.SynapseHttpServer;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The single source of truth for onboarding text, shared by the startup file
 * write and the in-game {@code /synapse} command. Values reflect this machine's
 * live config (port, bind, auth).
 */
public final class AgentDoc {

    private static final String PROMPT = """
            You can observe and control a running Minecraft client through a local
            HTTP API ("Synapse") at __BASE__ . It returns JSON ground truth, not pixels.

            First call GET __BASE__/manifest — it lists every endpoint, parameter, the
            /state schema, error codes, and tips. Treat it as the only docs you need.

            Loop:
              1. GET /state?mode=summary       -> where am I, holding/looking at what
              2. Act:
                   POST /cmd   "<command>"      (a level-4 Minecraft command)
                   POST /gui   {action:...}     (open/close/clickSlot/clickButton/type)
                   POST /player {action:...}    (look/move/use/attack/selectHotbar)
              3. Verify with GET /state (and GET /gui for screens).
                 GET /wait?ticks=N to let changes settle.
              4. Every response carries logs, context, and on failure an error with
                 code/message/hint -- read them and self-correct.

            Out of a world you get NOT_IN_WORLD (409). __AUTHHINT__
            /screenshot is only for visual sanity checks; prefer /state for logic.""";

    private AgentDoc() {
    }

    public static String baseUrl() {
        return "http://" + SynapseConfig.BIND_ADDRESS.get() + ":" + SynapseHttpServer.port();
    }

    public static boolean authEnabled() {
        return AuthToken.enabled();
    }

    /** The effective auth token for this machine (may be auto-generated). Empty if auth is off. */
    public static String authToken() {
        return AuthToken.effectiveToken();
    }

    public static Path docFile() {
        return FMLPaths.GAMEDIR.get().resolve("synapse").resolve("AGENT.md");
    }

    public static String docPathString() {
        return docFile().toAbsolutePath().toString();
    }

    /** The paste-ready agent prompt, with this machine's base URL and token substituted. */
    public static String agentPrompt() {
        return PROMPT
                .replace("__BASE__", baseUrl())
                .replace("__AUTHHINT__", authEnabled()
                        ? "Authenticate EVERY request with header  X-Synapse-Token: " + authToken()
                        : "No auth token is set.");
    }

    /** Shell snippet to attach the auth header in the curl examples (empty when auth is off). */
    private static String curlAuth() {
        return authEnabled() ? "-H \"X-Synapse-Token: $TOKEN\" " : "";
    }

    /** Best-effort: writes <gamedir>/synapse/AGENT.md, logging (not throwing) on failure. */
    public static void writeQuietly() {
        try {
            String md = "# Synapse — AI agent instructions\n\n"
                    + "Synapse exposes this running Minecraft client over a local HTTP API that\n"
                    + "returns JSON ground truth (not pixels). Generated on startup with this\n"
                    + "machine's settings.\n\n"
                    + "- Base URL: `" + baseUrl() + "`\n"
                    + "- Auth: " + (authEnabled()
                            ? "required — send header `X-Synapse-Token: " + authToken() + "`\n"
                              + "  (this machine-local token is auto-generated; it is also stored in `"
                              + AuthToken.tokenFile().toAbsolutePath() + "`)"
                            : "disabled (token empty; localhost only)") + "\n"
                    + "- Minecraft 1.20.1 (Forge), client-side only.\n\n"
                    + "## One-time setup (human)\n\n"
                    + "Commands and world state require being IN a world. Load a singleplayer\n"
                    + "world once, then type `/synapse` in chat to see this info in-game.\n\n"
                    + "## Give your AI agent this prompt\n\n"
                    + "----------------------------------------------------------------------\n"
                    + agentPrompt() + "\n"
                    + "----------------------------------------------------------------------\n\n"
                    + "## First moves (shell)\n\n"
                    + "```\n"
                    + (authEnabled() ? "TOKEN=" + authToken() + "\n" : "")
                    + "curl -s " + curlAuth() + baseUrl() + "/manifest | jq\n"
                    + "curl -s " + curlAuth() + "\"" + baseUrl() + "/state?mode=summary\" | jq .data\n"
                    + "curl -s -X POST " + curlAuth() + baseUrl() + "/cmd -d \"give @s minecraft:diamond 64\" | jq .data\n"
                    + "```\n";
            Files.createDirectories(docFile().getParent());
            Files.writeString(docFile(), md, StandardCharsets.UTF_8);
            Synapse.LOGGER.info("[Synapse] Wrote agent instructions to {}", docPathString());
        } catch (Throwable t) {
            Synapse.LOGGER.warn("[Synapse] Could not write agent doc: {}", t.toString());
        }
    }
}
