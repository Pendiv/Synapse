package dev.div.synapse.core;

import com.google.gson.JsonObject;
import dev.div.synapse.http.HttpUtil;
import dev.div.synapse.http.SynapseError;
import dev.div.synapse.http.SynapseException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Drives the local player's body: look, timed movement, use/attack, hotbar
 * selection (spec §11 v1.4) — public client APIs only.
 */
public final class PlayerController {

    private PlayerController() {
    }

    public static JsonObject act(JsonObject body, long timeoutMs) throws SynapseException {
        String action = HttpUtil.str(body, "action", "");
        return switch (action) {
            case "look" -> MainThread.run(() -> look(body), timeoutMs);
            case "selectHotbar" -> MainThread.run(() -> selectHotbar(body), timeoutMs);
            case "use" -> MainThread.run(() -> use(), timeoutMs);
            case "attack" -> MainThread.run(() -> attack(), timeoutMs);
            case "move" -> move(body, timeoutMs);
            default -> throw new SynapseException(SynapseError.BAD_REQUEST,
                    "Unknown player action '" + action + "'. Use look/move/use/attack/selectHotbar.");
        };
    }

    private static LocalPlayer require() throws SynapseException {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) {
            throw new SynapseException(SynapseError.NOT_IN_WORLD, "No player in world.");
        }
        return p;
    }

    private static JsonObject look(JsonObject body) throws SynapseException {
        LocalPlayer p = require();
        if (body.has("yaw")) {
            p.setYRot((float) HttpUtil.dbl(body, "yaw", p.getYRot()));
        }
        if (body.has("pitch")) {
            p.setXRot(clampPitch((float) HttpUtil.dbl(body, "pitch", p.getXRot())));
        }
        if (body.has("dyaw")) {
            p.setYRot(p.getYRot() + (float) HttpUtil.dbl(body, "dyaw", 0));
        }
        if (body.has("dpitch")) {
            p.setXRot(clampPitch(p.getXRot() + (float) HttpUtil.dbl(body, "dpitch", 0)));
        }
        JsonObject r = new JsonObject();
        r.addProperty("action", "look");
        r.addProperty("yaw", round(p.getYRot()));
        r.addProperty("pitch", round(p.getXRot()));
        return r;
    }

    private static JsonObject selectHotbar(JsonObject body) throws SynapseException {
        LocalPlayer p = require();
        int slot = HttpUtil.intval(body, "slot", -1);
        if (slot < 0 || slot > 8) {
            throw new SynapseException(SynapseError.BAD_REQUEST, "hotbar slot must be 0..8.");
        }
        p.getInventory().selected = slot;
        p.connection.send(new ServerboundSetCarriedItemPacket(slot));
        JsonObject r = new JsonObject();
        r.addProperty("action", "selectHotbar");
        r.addProperty("selected", slot);
        return r;
    }

    private static JsonObject use() throws SynapseException {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = require();
        InteractionHand hand = InteractionHand.MAIN_HAND;
        HitResult hr = mc.hitResult;
        JsonObject r = new JsonObject();
        r.addProperty("action", "use");
        InteractionResult result;
        if (hr instanceof BlockHitResult bhr && hr.getType() == HitResult.Type.BLOCK) {
            result = mc.gameMode.useItemOn(p, hand, bhr);
            // Vanilla falls through to a plain item use when the block didn't consume it.
            if (!result.consumesAction() && result != InteractionResult.FAIL) {
                InteractionResult itemResult = mc.gameMode.useItem(p, hand);
                if (itemResult.consumesAction()) {
                    result = itemResult;
                }
            }
            r.addProperty("on", "block");
        } else if (hr instanceof EntityHitResult ehr && hr.getType() == HitResult.Type.ENTITY) {
            // Try the precise interactAt, then the general interact (mount/trade/breed/leash).
            result = mc.gameMode.interactAt(p, ehr.getEntity(), ehr, hand);
            if (!result.consumesAction()) {
                result = mc.gameMode.interact(p, ehr.getEntity(), hand);
            }
            r.addProperty("on", "entity");
        } else {
            result = mc.gameMode.useItem(p, hand);
            r.addProperty("on", "air");
        }
        if (result.shouldSwing()) {
            p.swing(hand);
        }
        r.addProperty("result", result.toString());
        return r;
    }

    private static JsonObject attack() throws SynapseException {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = require();
        HitResult hr = mc.hitResult;
        JsonObject r = new JsonObject();
        r.addProperty("action", "attack");
        if (hr instanceof EntityHitResult ehr && hr.getType() == HitResult.Type.ENTITY) {
            mc.gameMode.attack(p, ehr.getEntity());
            p.swing(InteractionHand.MAIN_HAND);
            r.addProperty("on", "entity");
        } else if (hr instanceof BlockHitResult bhr && hr.getType() == HitResult.Type.BLOCK) {
            boolean started = mc.gameMode.startDestroyBlock(bhr.getBlockPos(), bhr.getDirection());
            p.swing(InteractionHand.MAIN_HAND);
            r.addProperty("on", "block");
            r.addProperty("started", started);
        } else {
            r.addProperty("on", "air");
        }
        return r;
    }

    private static JsonObject move(JsonObject body, long timeoutMs) throws SynapseException {
        final ScriptedInput si = new ScriptedInput();
        si.up = HttpUtil.bool(body, "forward", false);
        si.down = HttpUtil.bool(body, "back", false);
        si.left = HttpUtil.bool(body, "left", false);
        si.right = HttpUtil.bool(body, "right", false);
        si.jumping = HttpUtil.bool(body, "jump", false);
        si.shiftKeyDown = HttpUtil.bool(body, "sneak", false);
        final int ticks = Math.max(1, Math.min(HttpUtil.intval(body, "ticks", 10), 100));

        // Install the window on the main thread. It self-expires on the client
        // thread (Movement.tick) so the scripted input can never leak.
        MainThread.run(() -> {
            Movement.start(si, ticks);
            return null;
        }, timeoutMs);

        // Best-effort wait for completion — never fatal; the controller restores
        // input on its own regardless of what happens here.
        long startTick = TickClock.now();
        long deadline = System.currentTimeMillis() + ticks * 60L + 1500L;
        while (Movement.isActive() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        long elapsed = TickClock.now() - startTick;

        JsonObject r = MainThread.run(() -> {
            LocalPlayer p = require();
            JsonObject o = new JsonObject();
            JsonObject pos = new JsonObject();
            pos.addProperty("x", round(p.getX()));
            pos.addProperty("y", round(p.getY()));
            pos.addProperty("z", round(p.getZ()));
            o.add("pos", pos);
            return o;
        }, timeoutMs);
        r.addProperty("action", "move");
        r.addProperty("requestedTicks", ticks);
        r.addProperty("elapsedTicks", elapsed);
        return r;
    }

    private static float clampPitch(float pitch) {
        return Math.max(-90.0F, Math.min(90.0F, pitch));
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
