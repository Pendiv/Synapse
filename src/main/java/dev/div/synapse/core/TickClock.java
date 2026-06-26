package dev.div.synapse.core;

import dev.div.synapse.http.SynapseError;
import dev.div.synapse.http.SynapseException;
import net.minecraft.client.Minecraft;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts client ticks (via Forge {@link TickEvent.ClientTickEvent}) so handler
 * threads can synchronise with the game loop ({@code /wait}, timed movement).
 * Register an instance on the Forge event bus.
 */
public final class TickClock {

    private static final AtomicLong TICKS = new AtomicLong();

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // Refresh the response context snapshot every tick (incl. menus/paused), so
        // /state etc. need no extra main-thread hop just to fill 'context'.
        ContextCollector.capture();

        // Count only ticks where the world is actually progressing, so /wait and
        // timed movement track game time (and time out at the pause menu).
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.isPaused()) {
            return;
        }
        TICKS.incrementAndGet();
        Movement.tick();
    }

    public static long now() {
        return TICKS.get();
    }

    /**
     * Blocks the calling (handler) thread until {@code n} client ticks have
     * elapsed. Polls a tick counter the client thread advances — never call from
     * the main thread.
     *
     * @return the number of ticks actually elapsed
     * @throws SynapseException TIMEOUT if the deadline passes (e.g. game paused).
     */
    public static long waitTicks(long n, long timeoutMs) throws SynapseException {
        long start = TICKS.get();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (TICKS.get() - start < n) {
            if (System.currentTimeMillis() >= deadline) {
                throw new SynapseException(SynapseError.TIMEOUT,
                        "Waited " + (TICKS.get() - start) + "/" + n
                                + " ticks before timeout — the game may be paused or not ticking.");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SynapseException(SynapseError.INTERNAL, "Interrupted while waiting for ticks.", e);
            }
        }
        return TICKS.get() - start;
    }
}
