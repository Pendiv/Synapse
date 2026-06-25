package dev.div.synapse;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * Synapse — an AI-facing window into a running Minecraft client.
 *
 * <p>This mod runs an HTTP server inside the client JVM so an external AI can
 * observe ({@code /state}, {@code /screenshot}) and control ({@code /cmd}) the
 * game against ground-truth internal state. See {@code refs/Synapse_企画書.md}
 * for the full specification.
 *
 * <p>Phase A groundwork only: this is the clean entry point. The HTTP server,
 * config, and endpoint handlers ({@code http/}, {@code core/}, {@code config/})
 * are added in Phase 1.
 */
@Mod(Synapse.MODID)
public class Synapse {

    public static final String MODID = "synapse";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Synapse() {
        // Synapse is purely client-side; all setup happens on the client.
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onClientSetup);
    }

    private void onClientSetup(final FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            // TODO(Phase 1): LogCapture.install(); SynapseHttpServer.start();
            LOGGER.info("[Synapse] loaded — HTTP bridge not started yet (Phase 1 pending).");
        });
    }
}
