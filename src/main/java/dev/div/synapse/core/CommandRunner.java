package dev.div.synapse.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.div.synapse.config.SynapseConfig;
import dev.div.synapse.http.SynapseError;
import dev.div.synapse.http.SynapseException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Runs a Minecraft command at permission level 4 and captures its chat feedback
 * (spec §5.3). Execution happens on the integrated <b>server</b> thread (world
 * mutation must not run on the client thread); feedback is captured via a custom
 * {@link CommandSource}.
 */
public final class CommandRunner {

    private CommandRunner() {
    }

    /** Resolved server + player identity, captured on the client thread. */
    private record Resolved(IntegratedServer server, UUID uuid) {
    }

    /**
     * @return {@code { command, success, resultValue, feedback[], error? }}.
     *         Parse/execution failures are reported in the returned data
     *         (success=false, error message, captured feedback) rather than thrown,
     *         so the AI always gets the command's own diagnostics (spec §5.3/§4.1).
     * @throws SynapseException NOT_IN_WORLD (no singleplayer world) or BAD_REQUEST (empty command).
     */
    public static JsonObject run(String rawCommand, long timeoutMs) throws SynapseException {
        String stripped = rawCommand == null ? "" : rawCommand.strip();
        if (stripped.startsWith("/")) {
            stripped = stripped.substring(1).strip();
        }
        if (stripped.isEmpty()) {
            throw new SynapseException(SynapseError.BAD_REQUEST,
                    "Command is empty. POST the command text (leading slash optional).");
        }
        final String command = stripped;
        // DEVELOPER-mode commands run at this op level (4 = full; lower blocks op/stop/ban/etc.).
        final int permissionLevel = SynapseConfig.COMMAND_PERMISSION_LEVEL.get();

        // Resolve the server + player UUID on the client thread (no off-thread field reads).
        Resolved resolved = MainThread.run(() -> {
            Minecraft mc = Minecraft.getInstance();
            IntegratedServer server = mc.getSingleplayerServer();
            LocalPlayer player = mc.player;
            if (server == null || player == null) {
                throw new SynapseException(SynapseError.NOT_IN_WORLD,
                        "No integrated server — /cmd needs an active singleplayer world.")
                        .detail("currentScreen", mc.screen == null ? "none" : mc.screen.getClass().getSimpleName())
                        .detail("hint", "Enter a singleplayer world, then retry.");
            }
            return new Resolved(server, player.getUUID());
        }, timeoutMs);

        final IntegratedServer server = resolved.server();
        final UUID uuid = resolved.uuid();

        // Execute on the server thread.
        return MainThread.runOn(server, () -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
            if (sp == null) {
                throw new SynapseException(SynapseError.NOT_IN_WORLD,
                        "Server-side player not found for the current client.");
            }
            ServerLevel level = sp.serverLevel();

            List<String> feedback = new ArrayList<>();
            CommandSource capturing = new CommandSource() {
                @Override
                public void sendSystemMessage(Component message) {
                    feedback.add(message.getString());
                }

                @Override
                public boolean acceptsSuccess() {
                    return true;
                }

                @Override
                public boolean acceptsFailure() {
                    return true;
                }

                @Override
                public boolean shouldInformAdmins() {
                    return false;
                }
            };

            CommandSourceStack stack = new CommandSourceStack(
                    capturing,
                    sp.position(),
                    sp.getRotationVector(),
                    level,
                    permissionLevel,
                    sp.getName().getString(),
                    sp.getDisplayName(),
                    server,
                    sp);

            JsonObject data = new JsonObject();
            data.addProperty("command", command);
            try {
                int result = server.getCommands().getDispatcher().execute(command, stack);
                // Reaching here means the command ran without throwing = success.
                // The brigadier return value (often 0 for action commands that don't
                // report a count) goes in resultValue, NOT the success flag.
                data.addProperty("success", true);
                data.addProperty("resultValue", result);
            } catch (CommandSyntaxException e) {
                // Command failures (incl. selector misses, argument errors) are reported
                // in-band so the AI keeps the diagnostic — not turned into a 500.
                data.addProperty("success", false);
                data.addProperty("resultValue", 0);
                data.addProperty("error", e.getMessage());
            }

            JsonArray fb = new JsonArray();
            for (String s : feedback) {
                fb.add(s);
            }
            data.add("feedback", fb);
            return data;
        }, timeoutMs);
    }
}
