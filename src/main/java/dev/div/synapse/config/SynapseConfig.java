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
    public static final ForgeConfigSpec.ConfigValue<String> BIND_ADDRESS;
    public static final ForgeConfigSpec.ConfigValue<String> AUTH_TOKEN;
    public static final ForgeConfigSpec.IntValue TIMEOUT_MS;
    // [state]
    public static final ForgeConfigSpec.DoubleValue STATE_RADIUS;
    public static final ForgeConfigSpec.IntValue LOG_BUFFER_SIZE;
    // [features]
    public static final ForgeConfigSpec.BooleanValue SCREENSHOT_ENABLED;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.comment("Synapse — AI-facing HTTP bridge. Client-only.").push("server");
        PORT = b.comment("HTTP server listen port.")
                .defineInRange("port", 25599, 1, 65535);
        BIND_ADDRESS = b.comment("Bind address. Strongly recommend 127.0.0.1 (localhost only).")
                .define("bindAddress", "127.0.0.1");
        AUTH_TOKEN = b.comment("Auth token checked against the X-Synapse-Token header.",
                        "Empty string disables auth (a warning is logged at startup).")
                .define("authToken", "");
        TIMEOUT_MS = b.comment("Timeout (ms) for work scheduled onto the Minecraft main thread.")
                .defineInRange("timeoutMs", 5000, 100, 120_000);
        b.pop();

        b.push("state");
        STATE_RADIUS = b.comment("Radius (blocks) for the nearbyEntities scan in /state?mode=full.")
                .defineInRange("stateRadius", 16.0, 1.0, 256.0);
        LOG_BUFFER_SIZE = b.comment("Number of recent log lines included in every response's 'logs'.")
                .defineInRange("logBufferSize", 30, 0, 1000);
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
