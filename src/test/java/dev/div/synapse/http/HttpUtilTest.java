package dev.div.synapse.http;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for HttpUtil's pure JSON coercion and Content-Type predicate. */
class HttpUtilTest {

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void str_presentMissingAndCoercesNumber() {
        JsonObject o = obj("{\"a\":\"x\",\"n\":5}");
        assertEquals("x", HttpUtil.str(o, "a", "def"));
        assertEquals("def", HttpUtil.str(o, "missing", "def"));
        assertEquals("5", HttpUtil.str(o, "n", "def"));
    }

    @Test
    void intval_numberMissingDefaultAndTypeMismatchThrows() throws Exception {
        JsonObject o = obj("{\"n\":7,\"s\":\"x\"}");
        assertEquals(7, HttpUtil.intval(o, "n", -1));
        assertEquals(-1, HttpUtil.intval(o, "missing", -1));
        assertThrows(SynapseException.class, () -> HttpUtil.intval(o, "s", -1));
    }

    @Test
    void intval_truncatesDoubleTowardZero() throws Exception {
        // getAsInt() truncates; ops like /wait and /batch's wait read ticks via intval, so a
        // fractional value must floor (not round/throw).
        assertEquals(2, HttpUtil.intval(obj("{\"x\":2.9}"), "x", -1));
        assertEquals(-2, HttpUtil.intval(obj("{\"x\":-2.9}"), "x", -1));
    }

    @Test
    void dbl_numberDefaultAndTypeMismatchThrows() throws Exception {
        JsonObject o = obj("{\"d\":1.5,\"s\":\"x\"}");
        assertEquals(1.5, HttpUtil.dbl(o, "d", 0.0));
        assertEquals(9.0, HttpUtil.dbl(o, "missing", 9.0));
        assertThrows(SynapseException.class, () -> HttpUtil.dbl(o, "s", 0.0));
    }

    @Test
    void bool_valueDefaultAndTypeMismatchThrows() throws Exception {
        JsonObject o = obj("{\"b\":true,\"s\":\"x\"}");
        assertTrue(HttpUtil.bool(o, "b", false));
        assertTrue(HttpUtil.bool(o, "missing", true));
        assertThrows(SynapseException.class, () -> HttpUtil.bool(o, "s", false));
    }

    @Test
    void isJsonContentType_acceptsJsonRejectsSimpleTypesAndNull() {
        assertTrue(HttpUtil.isJsonContentType("application/json"));
        assertTrue(HttpUtil.isJsonContentType("application/json; charset=utf-8"));
        assertTrue(HttpUtil.isJsonContentType("  APPLICATION/JSON "));
        assertFalse(HttpUtil.isJsonContentType("text/plain"));
        assertFalse(HttpUtil.isJsonContentType("application/x-www-form-urlencoded"));
        assertFalse(HttpUtil.isJsonContentType(null));
    }
}
