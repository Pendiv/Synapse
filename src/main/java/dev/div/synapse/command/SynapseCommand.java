package dev.div.synapse.command;

import com.mojang.brigadier.context.CommandContext;
import dev.div.synapse.core.AgentDoc;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * The in-game {@code /synapse} client command. Being able to run it proves the
 * setup is complete (mod loaded, server up, in a world), and it hands the human
 * the base URL, the agent-prompt (copy to clipboard), and the AGENT.md path.
 */
public final class SynapseCommand {

    @SubscribeEvent
    public void onRegister(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("synapse")
                .executes(SynapseCommand::info)
                .then(Commands.literal("prompt").executes(SynapseCommand::prompt))
                .then(Commands.literal("status").executes(SynapseCommand::status)));
    }

    private static int info(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        String base = AgentDoc.baseUrl();
        src.sendSystemMessage(Component.literal("Synapse ").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
                .append(Component.literal("is listening on ").withStyle(ChatFormatting.GRAY))
                .append(urlLink(base))
                .append(Component.literal("  (auth: " + (AgentDoc.authEnabled() ? "on" : "off") + ")")
                        .withStyle(ChatFormatting.GRAY)));
        src.sendSystemMessage(Component.literal("Agent instructions: ").withStyle(ChatFormatting.GRAY)
                .append(fileLink(AgentDoc.docPathString())));
        if (AgentDoc.authEnabled()) {
            src.sendSystemMessage(Component.literal("Auth token: ").withStyle(ChatFormatting.GRAY)
                    .append(copyLink("[copy token]", AgentDoc.authToken(),
                            "Copy the ceiling-level auth token to your clipboard"))
                    .append(Component.literal(" — header X-Synapse-Token (the MCP finds it). Access ceiling: ")
                            .withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(AgentDoc.accessCeiling()).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(". Per-level tokens: ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(fileLink(AgentDoc.tokensPath())));
        }
        src.sendSystemMessage(Component.literal("Point your AI at the URL and have it call ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal("GET /manifest").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(". ").withStyle(ChatFormatting.GRAY))
                .append(copyLink("[copy prompt]", AgentDoc.agentPrompt(),
                        "Copy the paste-ready AI prompt to your clipboard"))
                .append(Component.literal(" ").withStyle(ChatFormatting.GRAY))
                .append(suggestLink("[status]", "/synapse status")));
        return 1;
    }

    private static int prompt(CommandContext<CommandSourceStack> ctx) {
        String prompt = AgentDoc.agentPrompt();
        ctx.getSource().sendSystemMessage(Component.literal("Agent prompt — ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal("[Click to copy]").withStyle(style -> style
                        .withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, prompt))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("Copy the paste-ready AI prompt to your clipboard"))))));
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        Minecraft mc = Minecraft.getInstance();
        boolean inWorld = mc.player != null && mc.level != null;
        String screen = mc.screen == null ? "none" : mc.screen.getClass().getSimpleName();
        ctx.getSource().sendSystemMessage(Component.literal(
                        "Synapse: " + AgentDoc.baseUrl()
                                + " | auth " + (AgentDoc.authEnabled() ? "on" : "off")
                                + " | access " + AgentDoc.accessCeiling()
                                + " | inWorld " + inWorld
                                + " | screen " + screen)
                .withStyle(ChatFormatting.GRAY));
        return 1;
    }

    private static MutableComponent urlLink(String url) {
        return Component.literal(url).withStyle(style -> style
                .withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Open in browser"))));
    }

    private static MutableComponent fileLink(String path) {
        return Component.literal(path).withStyle(style -> style
                .withColor(ChatFormatting.YELLOW)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, path))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to open"))));
    }

    // NOTE: a chat-click RUN_COMMAND routes through ClientPacketListener.sendUnsignedCommand, which
    // does NOT execute Forge client commands — it forwards them to the integrated server, which has
    // no /synapse, yielding "unknown or incomplete command". So for our client-only subcommands we
    // never use RUN_COMMAND from chat: we either copy a value directly, or SUGGEST the command so the
    // user submits it through the normal typed path (sendCommand), which does run client commands.
    private static MutableComponent suggestLink(String text, String command) {
        return Component.literal(text).withStyle(style -> style
                .withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal("Click to put " + command + " in chat, then press Enter"))));
    }

    private static MutableComponent copyLink(String text, String value, String tooltip) {
        return Component.literal(text).withStyle(style -> style
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, value))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(tooltip))));
    }
}
