package dev.div.synapse.core;

import dev.div.synapse.Synapse;
import dev.div.synapse.config.SynapseConfig;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes a self-contained onboarding file (<gamedir>/synapse/AGENT.md) at
 * startup, with this machine's live base URL and auth state baked in. Hand it
 * (or the prompt inside it) to an AI agent so it can drive this client.
 */
public final class AgentDoc {

    private static final String TEMPLATE = """
            # Synapse — AI agent instructions

            Synapse exposes this running Minecraft client over a local HTTP API that
            returns JSON ground truth (not pixels). This file was generated on startup
            with this machine's settings.

            - Base URL: `__BASE__`
            - Auth: __AUTH__
            - Minecraft: 1.20.1 (Forge), client-side only.

            ## One-time setup (human)

            Commands and world state require being IN a world. Before handing control to
            the AI, load a singleplayer world once (Synapse has no menu navigation yet).
            `GET __BASE__/state` returning `NOT_IN_WORLD` (409) means you're still on a menu.

            ## Give your AI agent this prompt

            ----------------------------------------------------------------------
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
              4. Every response carries `logs`, `context`, and on failure an `error`
                 with code/message/hint -- read them and self-correct.

            Out of a world you get NOT_IN_WORLD (409). __AUTHHINT__
            /screenshot is only for visual sanity checks; prefer /state for logic.
            ----------------------------------------------------------------------

            ## First moves (shell)

            ```
            curl -s __BASE__/manifest | jq
            curl -s "__BASE__/state?mode=summary" | jq .data
            curl -s -X POST __BASE__/cmd    -d "give @s minecraft:diamond 64" | jq .data
            curl -s -X POST __BASE__/gui    -d '{"action":"open","target":"inventory"}'
            curl -s -X POST __BASE__/player -d '{"action":"look","yaw":90,"pitch":0}'
            ```
            """;

    private AgentDoc() {
    }

    /** Best-effort: writes the doc, logging (not throwing) on failure. */
    public static void writeQuietly() {
        try {
            write();
        } catch (Throwable t) {
            Synapse.LOGGER.warn("[Synapse] Could not write agent doc: {}", t.toString());
        }
    }

    private static void write() throws IOException {
        String bind = SynapseConfig.BIND_ADDRESS.get();
        int port = SynapseConfig.PORT.get();
        boolean auth = !SynapseConfig.AUTH_TOKEN.get().isEmpty();
        String base = "http://" + bind + ":" + port;

        String md = TEMPLATE
                .replace("__BASE__", base)
                .replace("__AUTH__", auth
                        ? "required — send header `X-Synapse-Token: <your token>` on every request"
                        : "disabled (token empty; localhost only)")
                .replace("__AUTHHINT__", auth
                        ? "Send your token as the header X-Synapse-Token."
                        : "No auth token is set.");

        Path dir = FMLPaths.GAMEDIR.get().resolve("synapse");
        Files.createDirectories(dir);
        Path file = dir.resolve("AGENT.md");
        Files.writeString(file, md, StandardCharsets.UTF_8);
        Synapse.LOGGER.info("[Synapse] Wrote agent instructions to {}", file.toAbsolutePath());
    }
}
