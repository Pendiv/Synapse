package dev.div.synapse.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.div.synapse.config.SynapseConfig;
import dev.div.synapse.http.SynapseError;
import dev.div.synapse.http.SynapseException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Reads ground-truth client game state into JSON (spec §5.2). Must run on the
 * Minecraft main thread (schedule via {@link MainThread}). Provides a light
 * {@code summary} (every-loop) and a deep {@code full} (drill-down).
 */
public final class StateCollector {

    /** Hard cap on nearbyEntities to keep payloads bounded; truncation is reported. */
    private static final int MAX_NEARBY = 64;

    private StateCollector() {
    }

    public static JsonObject summary() throws SynapseException {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        requireInWorld(mc, player, level);
        JsonObject o = new JsonObject();
        o.addProperty("mode", "summary");
        fillSummary(o, mc, player, level);
        return o;
    }

    public static JsonObject full() throws SynapseException {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        requireInWorld(mc, player, level);
        JsonObject o = new JsonObject();
        o.addProperty("mode", "full");
        fillSummary(o, mc, player, level);
        fillFull(o, mc, player, level);
        return o;
    }

    private static void requireInWorld(Minecraft mc, LocalPlayer player, ClientLevel level) throws SynapseException {
        if (player == null || level == null) {
            throw new SynapseException(SynapseError.NOT_IN_WORLD,
                    "Not in a world — player or level is unavailable.")
                    .detail("currentScreen", screenName(mc))
                    .detail("hint", "Enter a world before calling /state. Check context.inWorld first.");
        }
    }

    // === summary ===

    private static void fillSummary(JsonObject o, Minecraft mc, LocalPlayer player, ClientLevel level) {
        o.addProperty("dimension", level.dimension().location().toString());

        JsonObject pos = new JsonObject();
        pos.addProperty("x", round(player.getX()));
        pos.addProperty("y", round(player.getY()));
        pos.addProperty("z", round(player.getZ()));
        BlockPos bp = player.blockPosition();
        pos.add("block", intArr(bp.getX(), bp.getY(), bp.getZ()));
        o.add("pos", pos);

        JsonObject rot = new JsonObject();
        rot.addProperty("yaw", round(player.getYRot()));
        rot.addProperty("pitch", round(player.getXRot()));
        o.add("rotation", rot);

        o.addProperty("health", round(player.getHealth()));
        o.addProperty("food", player.getFoodData().getFoodLevel());
        o.addProperty("gameMode", mc.gameMode == null ? "unknown" : mc.gameMode.getPlayerMode().getName());
        o.add("lookingAt", lookingAt(mc, level));
        o.addProperty("screen", screenName(mc));

        Inventory inv = player.getInventory();
        JsonArray hotbar = new JsonArray();
        for (int i = 0; i <= 8; i++) {
            hotbar.add(slotItem(i, inv.getItem(i)));
        }
        o.add("hotbar", hotbar);
        o.addProperty("selectedSlot", inv.selected);
    }

    private static JsonObject lookingAt(Minecraft mc, ClientLevel level) {
        JsonObject o = new JsonObject();
        HitResult hr = mc.hitResult;
        if (hr == null || hr.getType() == HitResult.Type.MISS) {
            o.addProperty("type", "miss");
            return o;
        }
        if (hr.getType() == HitResult.Type.BLOCK && hr instanceof BlockHitResult bhr) {
            o.addProperty("type", "block");
            BlockPos p = bhr.getBlockPos();
            o.addProperty("block", keyOf(ForgeRegistries.BLOCKS.getKey(level.getBlockState(p).getBlock())));
            o.add("blockPos", intArr(p.getX(), p.getY(), p.getZ()));
            o.addProperty("face", bhr.getDirection().getName());
        } else if (hr.getType() == HitResult.Type.ENTITY && hr instanceof EntityHitResult ehr) {
            Entity e = ehr.getEntity();
            o.addProperty("type", "entity");
            o.addProperty("entity", keyOf(ForgeRegistries.ENTITY_TYPES.getKey(e.getType())));
            o.addProperty("entityId", e.getId());
            o.add("pos", doubleArr(e.getX(), e.getY(), e.getZ()));
        } else {
            o.addProperty("type", "miss");
        }
        return o;
    }

    // === full ===

    private static void fillFull(JsonObject o, Minecraft mc, LocalPlayer player, ClientLevel level) {
        Inventory inv = player.getInventory();

        JsonObject inventory = new JsonObject();
        JsonArray main = new JsonArray();
        for (int i = 0; i < inv.items.size(); i++) {
            main.add(slotItem(i, inv.items.get(i)));
        }
        inventory.add("main", main);
        // Inventory.armor is indexed feet(0), legs(1), chest(2), head(3). Emit helmet-first
        // with explicit equip labels so position never has to be guessed (spec lists
        // armor as [helmet, chest, legs, boots]).
        JsonArray armor = new JsonArray();
        String[] equip = {"feet", "legs", "chest", "head"};
        for (int i = inv.armor.size() - 1; i >= 0; i--) {
            JsonObject so = slotItem(i, inv.armor.get(i));
            so.addProperty("equip", equip[i]);
            armor.add(so);
        }
        inventory.add("armor", armor);
        inventory.add("offhand", slotItem(0, inv.offhand.get(0)));
        o.add("inventory", inventory);

        // Open container (only when a non-inventory menu is open).
        AbstractContainerMenu menu = player.containerMenu;
        if (menu != null && menu != player.inventoryMenu) {
            JsonObject container = new JsonObject();
            container.addProperty("type", menuType(menu));
            JsonArray slots = new JsonArray();
            for (int i = 0; i < menu.slots.size(); i++) {
                Slot s = menu.slots.get(i);
                JsonObject so = slotItem(i, s.getItem());
                so.addProperty("containerSlot", s.getContainerSlot());
                slots.add(so);
            }
            container.add("slots", slots);
            o.add("openContainer", container);
        }

        // Attributes (only those present on the player).
        JsonObject attrs = new JsonObject();
        putAttr(attrs, player, "maxHealth", Attributes.MAX_HEALTH);
        putAttr(attrs, player, "movementSpeed", Attributes.MOVEMENT_SPEED);
        putAttr(attrs, player, "attackDamage", Attributes.ATTACK_DAMAGE);
        putAttr(attrs, player, "attackSpeed", Attributes.ATTACK_SPEED);
        putAttr(attrs, player, "armor", Attributes.ARMOR);
        putAttr(attrs, player, "armorToughness", Attributes.ARMOR_TOUGHNESS);
        putAttr(attrs, player, "knockbackResistance", Attributes.KNOCKBACK_RESISTANCE);
        putAttr(attrs, player, "followRange", Attributes.FOLLOW_RANGE);
        putAttr(attrs, player, "luck", Attributes.LUCK);
        o.add("attributes", attrs);

        // Effects.
        JsonArray effects = new JsonArray();
        Collection<MobEffectInstance> active = player.getActiveEffects();
        for (MobEffectInstance mei : active) {
            JsonObject e = new JsonObject();
            e.addProperty("effect", keyOf(ForgeRegistries.MOB_EFFECTS.getKey(mei.getEffect())));
            e.addProperty("amplifier", mei.getAmplifier());
            e.addProperty("duration", mei.getDuration());
            effects.add(e);
        }
        o.add("effects", effects);

        // Experience.
        JsonObject xp = new JsonObject();
        xp.addProperty("level", player.experienceLevel);
        xp.addProperty("progress", round(player.experienceProgress));
        xp.addProperty("total", player.totalExperience);
        o.add("experience", xp);

        // World.
        BlockPos bp = player.blockPosition();
        JsonObject world = new JsonObject();
        world.addProperty("time", level.getGameTime());
        world.addProperty("dayTime", level.getDayTime());
        world.addProperty("weather", level.isThundering() ? "thunder" : (level.isRaining() ? "rain" : "clear"));
        world.addProperty("biome", level.getBiome(bp).unwrapKey()
                .map(k -> k.location().toString()).orElse("unknown"));
        JsonObject light = new JsonObject();
        light.addProperty("block", level.getBrightness(LightLayer.BLOCK, bp));
        light.addProperty("sky", level.getBrightness(LightLayer.SKY, bp));
        world.add("lightLevel", light);
        world.addProperty("difficulty", level.getDifficulty().getKey());
        o.add("world", world);

        // Nearby entities.
        double radius = SynapseConfig.STATE_RADIUS.get();
        AABB box = player.getBoundingBox().inflate(radius);
        List<Entity> found = level.getEntities(player, box, e -> e != player);
        found.sort((a, b) -> Double.compare(player.distanceToSqr(a), player.distanceToSqr(b)));
        JsonArray nearby = new JsonArray();
        int count = Math.min(found.size(), MAX_NEARBY);
        for (int i = 0; i < count; i++) {
            Entity e = found.get(i);
            JsonObject eo = new JsonObject();
            eo.addProperty("type", keyOf(ForgeRegistries.ENTITY_TYPES.getKey(e.getType())));
            eo.addProperty("id", e.getId());
            eo.add("pos", doubleArr(e.getX(), e.getY(), e.getZ()));
            eo.addProperty("distance", round(player.distanceTo(e)));
            if (e instanceof LivingEntity le) {
                eo.addProperty("health", round(le.getHealth()));
            }
            nearby.add(eo);
        }
        o.add("nearbyEntities", nearby);
        if (found.size() > count) {
            o.addProperty("nearbyEntitiesTruncated", found.size() - count);
        }

        // Raycast detail + render info.
        o.add("raycast", lookingAt(mc, level));
        o.addProperty("fps", mc.getFps());
        o.addProperty("renderDistance", mc.options.renderDistance().get());
    }

    // === helpers ===

    private static void putAttr(JsonObject attrs, LivingEntity e, String key, Attribute attr) {
        if (e.getAttribute(attr) != null) {
            attrs.addProperty(key, round(e.getAttributeValue(attr)));
        }
    }

    private static String menuType(AbstractContainerMenu menu) {
        try {
            ResourceLocation k = ForgeRegistries.MENU_TYPES.getKey(menu.getType());
            return k == null ? "unknown" : k.toString();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static JsonObject slotItem(int slot, ItemStack stack) {
        JsonObject o = new JsonObject();
        o.addProperty("slot", slot);
        o.addProperty("item", itemId(stack));
        o.addProperty("count", stack.getCount());
        if (stack.hasTag()) {
            o.addProperty("nbtPresent", true);
        }
        return o;
    }

    private static String itemId(ItemStack stack) {
        if (stack.isEmpty()) {
            return "minecraft:air";
        }
        return keyOf(ForgeRegistries.ITEMS.getKey(stack.getItem()));
    }

    private static String keyOf(ResourceLocation key) {
        return key == null ? "unknown" : key.toString();
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String screenName(Minecraft mc) {
        return mc.screen == null ? "none" : mc.screen.getClass().getSimpleName();
    }

    private static JsonArray intArr(int... vals) {
        JsonArray a = new JsonArray();
        for (int v : vals) {
            a.add(v);
        }
        return a;
    }

    private static JsonArray doubleArr(double... vals) {
        JsonArray a = new JsonArray();
        for (double v : vals) {
            a.add(round(v));
        }
        return a;
    }
}
