package dev.div.synapse;

import com.mojang.logging.LogUtils;
import dev.div.synapse.command.SynapseCommand;
import dev.div.synapse.config.SynapseConfig;
import dev.div.synapse.core.AgentDoc;
import dev.div.synapse.core.ChatCapture;
import dev.div.synapse.core.LogCapture;
import dev.div.synapse.core.TickClock;
import dev.div.synapse.http.SynapseHttpServer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * Synapse — an AI-facing window into a running Minecraft client.
 *
 * <p>Runs a local HTTP server inside the client JVM so an external AI can observe
 * ({@code /state}, {@code /screenshot}) and control ({@code /cmd}) the game against
 * ground-truth internal state. See {@code refs/Synapse_企画書.md} for the full spec.
 */
@Mod(Synapse.MODID)
public class Synapse {

    public static final String MODID = "synapse";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Synapse() {
        // Client-only. On a dedicated server the client-setup event never fires,
        // so the bridge simply does nothing there.
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return;
        }
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, SynapseConfig.SPEC);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onClientSetup);
    }

    private void onClientSetup(final FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            LogCapture.install();
            // Forge-bus subscribers for tick counting (/wait, timed move) and chat capture (/chat).
            MinecraftForge.EVENT_BUS.register(new TickClock());
            MinecraftForge.EVENT_BUS.register(new ChatCapture());
            MinecraftForge.EVENT_BUS.register(new SynapseCommand()); // in-game /synapse onboarding command
            SynapseHttpServer.start();
            // Drop a machine-local onboarding file (with this host's port/auth) for AI agents.
            AgentDoc.writeQuietly();
        });
    }
}
