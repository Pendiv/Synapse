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
     * Sends a chat message, or a command if it starts with {@code /}. Must run on
     * the main thread.
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
        if (t.startsWith("/")) {
            player.connection.sendCommand(t.substring(1));
        } else {
            player.connection.sendChat(t);
        }
    }
}
