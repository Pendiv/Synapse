package dev.div.synapse.core;

import com.google.gson.JsonArray;
import dev.div.synapse.http.SynapseError;
import dev.div.synapse.http.SynapseException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Captures received chat into a bounded ring buffer (so {@code GET /chat} can
 * return it) and sends chat/commands on behalf of the player. Register an
 * instance on the Forge event bus.
 */
public final class ChatCapture {

    private static final int MAX = 100;
    private static final ConcurrentLinkedDeque<String> BUFFER = new ConcurrentLinkedDeque<>();

    @SubscribeEvent
    public void onChatReceived(ClientChatReceivedEvent event) {
        if (event.getMessage() != null) {
            BUFFER.addLast(event.getMessage().getString());
            while (BUFFER.size() > MAX) {
                BUFFER.pollFirst();
            }
        }
    }

    /** Recent received chat lines (oldest → newest). */
    public static JsonArray recentAsJson() {
        JsonArray arr = new JsonArray();
        for (String s : BUFFER) {
            arr.add(s);
        }
        return arr;
    }

    /**
     * Sends a plain chat message. Must run on the main thread.
     *
     * <p>Commands are deliberately NOT executed here: {@code player.connection.sendCommand}
     * runs at the player's own server op level, which would sidestep
     * {@code commandPermissionLevel}. The handlers route a {@code '/'}-prefixed text through
     * {@link CommandRunner} instead, so a string reaching this method is always plain chat.
     */
    public static void send(String text) throws SynapseException {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            throw new SynapseException(SynapseError.NOT_IN_WORLD, "No player — cannot send chat.");
        }
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) {
            throw new SynapseException(SynapseError.BAD_REQUEST, "Empty chat text.");
        }
        player.connection.sendChat(t);
    }
}
