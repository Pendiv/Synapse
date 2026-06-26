package dev.div.synapse.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Client-side config (spec §6), serialised to {@code synapse-client.toml}.
 *
 * <p>Values are read lazily via {@code .get()} once the config is loaded
 * (by the time {@code FMLClientSetupEvent} fires, which is where the HTTP
 * server starts).
 */
public final class SynapseConfig {

    public static final ForgeConfigSpec SPEC;

    // [server]
    public static final ForgeConfigSpec.IntValue PORT;
    public static final ForgeConfigSpec.BooleanValue AUTO_PORT;
    public static final ForgeConfigSpec.ConfigValue<String> BIND_ADDRESS;
    public static final ForgeConfigSpec.ConfigValue<String> AUTH_TOKEN;
    public static final ForgeConfigSpec.EnumValue<AccessLevel> ACCESS_LEVEL;
    public static final ForgeConfigSpec.IntValue COMMAND_PERMISSION_LEVEL;
    public static final ForgeConfigSpec.IntValue TIMEOUT_MS;
    public static final ForgeConfigSpec.IntValue MAX_BODY_BYTES;
    public static final ForgeConfigSpec.IntValue BATCH_BUDGET_MS;
    // [state]
    public static final ForgeConfigSpec.DoubleValue STATE_RADIUS;
    public static final ForgeConfigSpec.IntValue LOG_BUFFER_SIZE;
    public static final ForgeConfigSpec.BooleanValue CAPTURE_ALL_MOD_LOGS;
    // [features]
    public static final ForgeConfigSpec.BooleanValue SCREENSHOT_ENABLED;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.comment("Synapse — AI-facing HTTP bridge. Client-only.").push("server");
        PORT = b.comment("HTTP server listen port.")
                .defineInRange("port", 25599, 1, 65535);
        AUTO_PORT = b.comment("If the configured port is busy, bind the next free port instead.",
                        "Lets several Minecraft instances run Synapse at once; each advertises its",
                        "actual port in ~/.synapse/instances.json so one AI can drive them all.")
                .define("autoPort", true);
        BIND_ADDRESS = b.comment("Bind address. Strongly recommend 127.0.0.1 (localhost only).")
                .define("bindAddress", "127.0.0.1");
        AUTH_TOKEN = b.comment("Auth token checked against the X-Synapse-Token header.",
                        "Leave EMPTY for secure-by-default: Synapse auto-generates a strong random token on",
                        "first run and saves it to ~/.synapse/token (the bundled MCP server reads it from there,",
                        "and /synapse prints it; AGENT.md points to the file). Set an explicit value to manage your own secret.")
                .define("authToken", "");
        ACCESS_LEVEL = b.comment("How much the AI may do (gates every mutating op, so even a prompt-injected agent is bounded):",
                        "  OBSERVE   - read-only (state, gui/chat reads, screenshot, wait).",
                        "  PLAY      - observe + what a human player could do (move, click, plain chat). [default]",
                        "  DEVELOPER - play + arbitrary commands (/cmd, '/'-chat, creative inventory). FULL POWER,",
                        "              AT YOUR OWN RISK: a misled AI can run any level-commandPermissionLevel command.",
                        "Changing this takes effect on restart (the mirrored ~/.synapse/token tracks the ceiling then).")
                .defineEnum("accessLevel", AccessLevel.PLAY);
        COMMAND_PERMISSION_LEVEL = b.comment("Op permission level that DEVELOPER-mode commands run at (0-4).",
                        "4 = full (current behaviour). Lower it (e.g. 2) to allow give/tp/time/summon but block",
                        "op/stop/ban/deop, so a developer-mode AI still cannot escalate or break the server.")
                .defineInRange("commandPermissionLevel", 4, 0, 4);
        TIMEOUT_MS = b.comment("Timeout (ms) for work scheduled onto the Minecraft main thread.")
                .defineInRange("timeoutMs", 5000, 100, 120_000);
        MAX_BODY_BYTES = b.comment("Maximum accepted request body size in bytes (guards against memory-exhaustion).",
                        "Commands and JSON payloads are tiny; the default 1 MiB is generous.")
                .defineInRange("maxBodyBytes", 1_048_576, 1_024, 67_108_864);
        BATCH_BUDGET_MS = b.comment("Wall-clock budget (ms) for one POST /batch request across all its ops.",
                        "Bounds how long a single batch can hold a worker thread (e.g. several /wait ops);",
                        "ops past the budget are reported as skipped. Keep it >= one /wait (~37s).")
                .defineInRange("batchBudgetMs", 60_000, 1_000, 600_000);
        b.pop();

        b.push("state");
        STATE_RADIUS = b.comment("Radius (blocks) for the nearbyEntities scan in /state?mode=full.")
                .defineInRange("stateRadius", 16.0, 1.0, 256.0);
        LOG_BUFFER_SIZE = b.comment("Number of recent log lines included in every response's 'logs'.")
                .defineInRange("logBufferSize", 30, 0, 1000);
        CAPTURE_ALL_MOD_LOGS = b.comment("If false (default), 'logs' carries only Synapse's own log lines.",
                        "If true, it captures the whole game's root log (other mods, MC internals) — richer",
                        "for AI self-correction, but may expose file paths, server addresses, and other mods'",
                        "diagnostics to any client that can read a response.")
                .define("captureAllModLogs", false);
        b.pop();

        b.push("features");
        SCREENSHOT_ENABLED = b.comment("Enable the /screenshot endpoint.")
                .define("screenshotEnabled", true);
        b.pop();

        SPEC = b.build();
    }

    private SynapseConfig() {
    }
}
