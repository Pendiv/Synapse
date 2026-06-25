package dev.div.synapse.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.div.synapse.http.HttpUtil;
import dev.div.synapse.http.SynapseError;
import dev.div.synapse.http.SynapseException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Observes and drives the open {@link Screen} (spec §11 v1.3/v1.4) using public
 * client APIs only — no mixin. Everything runs on the Minecraft main thread.
 */
public final class GuiController {

    private GuiController() {
    }

    /** Snapshot of the open screen: class, title, widgets, and container slots. */
    public static JsonObject observe(long timeoutMs) throws SynapseException {
        return MainThread.run(() -> {
            Minecraft mc = Minecraft.getInstance();
            Screen screen = mc.screen;
            JsonObject o = new JsonObject();
            if (screen == null) {
                o.addProperty("screen", "none");
                o.addProperty("kind", "none");
                return o;
            }
            o.addProperty("screen", screen.getClass().getSimpleName());
            o.addProperty("title", screen.getTitle() == null ? null : screen.getTitle().getString());
            o.addProperty("width", screen.width);
            o.addProperty("height", screen.height);

            JsonArray widgets = new JsonArray();
            List<AbstractWidget> all = collectWidgets(screen);
            for (int i = 0; i < all.size(); i++) {
                AbstractWidget w = all.get(i);
                JsonObject wo = new JsonObject();
                wo.addProperty("index", i);
                wo.addProperty("type", w.getClass().getSimpleName());
                wo.addProperty("label", w.getMessage() == null ? "" : w.getMessage().getString());
                wo.addProperty("x", w.getX());
                wo.addProperty("y", w.getY());
                wo.addProperty("w", w.getWidth());
                wo.addProperty("h", w.getHeight());
                wo.addProperty("active", w.isActive());
                wo.addProperty("visible", w.visible);
                if (w instanceof EditBox eb) {
                    wo.addProperty("value", eb.getValue());
                    wo.addProperty("editable", true);
                }
                if (w instanceof AbstractButton) {
                    wo.addProperty("button", true);
                }
                widgets.add(wo);
            }
            o.add("widgets", widgets);
            o.addProperty("widgetCount", all.size());

            if (screen instanceof AbstractContainerScreen<?> cs) {
                o.addProperty("kind", "container");
                JsonObject container = new JsonObject();
                AbstractContainerMenu menu = cs.getMenu();
                container.addProperty("containerId", menu.containerId);
                JsonArray slots = new JsonArray();
                for (int i = 0; i < menu.slots.size(); i++) {
                    Slot s = menu.slots.get(i);
                    JsonObject so = new JsonObject();
                    so.addProperty("slot", i);
                    so.addProperty("item", itemId(s.getItem()));
                    so.addProperty("count", s.getItem().getCount());
                    so.addProperty("container", s.getContainerSlot());
                    slots.add(so);
                }
                container.add("slots", slots);
                container.addProperty("carried", itemId(menu.getCarried()));
                o.add("container", container);
            } else {
                o.addProperty("kind", "screen");
            }
            return o;
        }, timeoutMs);
    }

    /** Performs a GUI action: open / close / clickSlot / clickButton / type. */
    public static JsonObject act(JsonObject body, long timeoutMs) throws SynapseException {
        String action = HttpUtil.str(body, "action", "");
        return MainThread.run(() -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            if (player == null) {
                throw new SynapseException(SynapseError.NOT_IN_WORLD, "No player in world.");
            }
            JsonObject res = new JsonObject();
            res.addProperty("action", action);
            switch (action) {
                case "open" -> {
                    String target = HttpUtil.str(body, "target", "inventory");
                    switch (target) {
                        case "inventory" -> mc.setScreen(new InventoryScreen(player));
                        case "creative" -> mc.setScreen(new CreativeModeInventoryScreen(player,
                                player.connection.enabledFeatures(), mc.options.operatorItemsTab().get()));
                        default -> throw new SynapseException(SynapseError.BAD_REQUEST,
                                "open target must be 'inventory' or 'creative', got '" + target + "'.");
                    }
                    res.addProperty("opened", target);
                    // Report what actually ended up open (creative mode redirects inventory->creative).
                    res.addProperty("screen", mc.screen == null ? "none" : mc.screen.getClass().getSimpleName());
                }
                case "close" -> {
                    if (player.containerMenu != player.inventoryMenu) {
                        player.closeContainer(); // real container: notify server + clear the screen
                    } else {
                        mc.setScreen(null);      // plain screen (menu/config): just clear it
                    }
                    res.addProperty("closed", true);
                }
                case "clickSlot" -> {
                    if (!(mc.screen instanceof AbstractContainerScreen<?>)) {
                        throw new SynapseException(SynapseError.BAD_REQUEST,
                                "No container screen open to click a slot in.");
                    }
                    if (mc.screen instanceof CreativeModeInventoryScreen) {
                        throw new SynapseException(SynapseError.BAD_REQUEST,
                                "clickSlot is not supported in the creative inventory (creative syncs items "
                                        + "differently). Switch to survival mode, or use POST /cmd 'give ...'.");
                    }
                    if (!body.has("slot")) {
                        throw new SynapseException(SynapseError.BAD_REQUEST, "clickSlot requires a 'slot' index.");
                    }
                    int slot = HttpUtil.intval(body, "slot", -1);
                    int size = player.containerMenu.slots.size();
                    if (slot != -999 && (slot < 0 || slot >= size)) {
                        throw new SynapseException(SynapseError.BAD_REQUEST,
                                "slot must be 0.." + (size - 1) + " (or -999 to drop the carried stack).");
                    }
                    int button = HttpUtil.intval(body, "button", 0);
                    String mode = HttpUtil.str(body, "mode", "PICKUP");
                    ClickType type;
                    try {
                        type = ClickType.valueOf(mode);
                    } catch (IllegalArgumentException e) {
                        throw new SynapseException(SynapseError.BAD_REQUEST,
                                "mode must be one of PICKUP/QUICK_MOVE/SWAP/CLONE/THROW/QUICK_CRAFT/PICKUP_ALL.");
                    }
                    mc.gameMode.handleInventoryMouseClick(player.containerMenu.containerId, slot, button, type, player);
                    res.addProperty("clickedSlot", slot);
                    res.addProperty("carriedAfter", itemId(player.containerMenu.getCarried()));
                }
                case "clickButton" -> {
                    AbstractWidget w = findWidget(mc.screen, body);
                    if (!(w instanceof AbstractButton b)) {
                        throw new SynapseException(SynapseError.BAD_REQUEST,
                                "No matching button (give 'index' or 'label').");
                    }
                    if (!b.isActive()) {
                        throw new SynapseException(SynapseError.BAD_REQUEST,
                                "Button '" + b.getMessage().getString() + "' is not active.");
                    }
                    b.onPress();
                    res.addProperty("pressed", b.getMessage().getString());
                }
                case "type" -> {
                    AbstractWidget w = findWidget(mc.screen, body);
                    if (!(w instanceof EditBox eb)) {
                        throw new SynapseException(SynapseError.BAD_REQUEST,
                                "No matching text field (give 'index' or 'label').");
                    }
                    String text = HttpUtil.str(body, "text", "");
                    boolean replace = HttpUtil.bool(body, "replace", true);
                    eb.setFocused(true);
                    if (replace) {
                        eb.setValue(text);
                    } else {
                        eb.insertText(text);
                    }
                    res.addProperty("value", eb.getValue());
                }
                default -> throw new SynapseException(SynapseError.BAD_REQUEST,
                        "Unknown gui action '" + action + "'. Use open/close/clickSlot/clickButton/type.");
            }
            return res;
        }, timeoutMs);
    }

    /**
     * All interactable widgets on a screen: those in {@code renderables} first,
     * then any in {@code children()} not already seen (e.g. an addWidget-but-not-
     * rendered box like the creative search field). Deduped by identity; the index
     * is the position in this list (stable between observe and act).
     */
    private static List<AbstractWidget> collectWidgets(Screen screen) {
        List<AbstractWidget> out = new ArrayList<>();
        Set<AbstractWidget> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Renderable r : screen.renderables) {
            if (r instanceof AbstractWidget w && seen.add(w)) {
                out.add(w);
            }
        }
        for (GuiEventListener g : screen.children()) {
            if (g instanceof AbstractWidget w && seen.add(w)) {
                out.add(w);
            }
        }
        return out;
    }

    /** Finds a widget by {@code index} (over {@link #collectWidgets}) or by {@code label} (exact, else contains). */
    private static AbstractWidget findWidget(Screen screen, JsonObject body) throws SynapseException {
        if (screen == null) {
            return null;
        }
        List<AbstractWidget> all = collectWidgets(screen);
        if (body.has("index")) {
            int idx = HttpUtil.intval(body, "index", -1);
            return idx >= 0 && idx < all.size() ? all.get(idx) : null;
        }
        String label = HttpUtil.str(body, "label", null);
        if (label == null || label.isEmpty()) {
            return null;
        }
        AbstractWidget contains = null;
        for (AbstractWidget w : all) {
            String msg = w.getMessage() == null ? "" : w.getMessage().getString();
            if (msg.equalsIgnoreCase(label)) {
                return w;
            }
            if (contains == null && msg.toLowerCase().contains(label.toLowerCase())) {
                contains = w;
            }
        }
        return contains;
    }

    private static String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "minecraft:air";
        }
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return key == null ? "unknown" : key.toString();
    }
}
