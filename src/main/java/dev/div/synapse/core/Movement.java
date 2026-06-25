package dev.div.synapse.core;

import dev.div.synapse.http.SynapseError;
import dev.div.synapse.http.SynapseException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;

/**
 * Tick-driven scripted movement. A single movement window is active at a time;
 * it self-expires and restores the keyboard input <b>on the client thread</b>
 * (driven from {@link TickClock#tick()}), so a scripted input can never leak
 * even if the requesting HTTP thread dies, times out, or races another request.
 */
public final class Movement {

    private static final Object LOCK = new Object();
    private static ScriptedInput active; // currently installed scripted input, or null
    private static Input saved;          // input to restore (never a ScriptedInput)
    private static LocalPlayer owner;    // the player it was installed on
    private static int remaining;        // world ticks left

    private Movement() {
    }

    /** Starts (replacing any current) a movement window. Must run on the main thread. */
    public static void start(ScriptedInput input, int ticks) throws SynapseException {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            throw new SynapseException(SynapseError.NOT_IN_WORLD, "No player in world.");
        }
        synchronized (LOCK) {
            restoreLocked(mc);
            // Never capture a scripted input as the baseline to restore to.
            saved = player.input instanceof ScriptedInput ? new KeyboardInput(mc.options) : player.input;
            owner = player;
            active = input;
            remaining = Math.max(1, ticks);
            player.input = input;
        }
    }

    /** Advances/expires the active movement. Called once per world tick on the client thread. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        synchronized (LOCK) {
            if (active == null) {
                return;
            }
            LocalPlayer player = mc.player;
            // Player replaced (respawn/dimension) or our input was swapped out — drop safely.
            if (player == null || player != owner || player.input != active) {
                active = null;
                saved = null;
                owner = null;
                remaining = 0;
                return;
            }
            if (--remaining <= 0) {
                restoreLocked(mc);
            }
        }
    }

    public static boolean isActive() {
        synchronized (LOCK) {
            return active != null;
        }
    }

    private static void restoreLocked(Minecraft mc) {
        if (active != null && owner != null && owner.input == active) {
            owner.input = saved != null ? saved : new KeyboardInput(mc.options);
        }
        active = null;
        saved = null;
        owner = null;
        remaining = 0;
    }
}
