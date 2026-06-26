package dev.div.synapse.http;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.div.synapse.config.AccessLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure level→operation policy (no config/Minecraft needed). */
class AccessControlPolicyTest {

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void guiOpenCreativeNeedsDeveloperEverythingElsePlay() {
        assertEquals(AccessLevel.DEVELOPER, AccessControl.levelForGui(obj("{\"action\":\"open\",\"target\":\"creative\"}")));
        assertEquals(AccessLevel.DEVELOPER, AccessControl.levelForGui(obj("{\"action\":\"OPEN\",\"target\":\"CREATIVE\"}")));
        assertEquals(AccessLevel.PLAY, AccessControl.levelForGui(obj("{\"action\":\"open\",\"target\":\"inventory\"}")));
        assertEquals(AccessLevel.PLAY, AccessControl.levelForGui(obj("{\"action\":\"clickSlot\",\"slot\":0}")));
        assertEquals(AccessLevel.PLAY, AccessControl.levelForGui(obj("{}")));
    }

    @Test
    void chatCommandNeedsDeveloperPlainMessageIsPlay() {
        assertEquals(AccessLevel.DEVELOPER, AccessControl.levelForChat("/time set day"));
        assertEquals(AccessLevel.DEVELOPER, AccessControl.levelForChat("   /op me"));
        assertEquals(AccessLevel.PLAY, AccessControl.levelForChat("hello world"));
        assertEquals(AccessLevel.PLAY, AccessControl.levelForChat(""));
    }

    @Test
    void isCommandDetectsLeadingSlash() {
        assertTrue(AccessControl.isCommand("/give @s diamond"));
        assertTrue(AccessControl.isCommand("  /tp"));
        assertFalse(AccessControl.isCommand("just chatting"));
        assertFalse(AccessControl.isCommand(null));
    }
}
