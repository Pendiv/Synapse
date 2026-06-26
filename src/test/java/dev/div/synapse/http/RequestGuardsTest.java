package dev.div.synapse.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the security-critical request guards (no Minecraft runtime needed). */
class RequestGuardsTest {

    private static final int PORT = 25599;

    @Test
    void hostAllowed_loopbackNamesOnMatchingPort() {
        assertTrue(RequestGuards.hostAllowed("127.0.0.1:25599", PORT));
        assertTrue(RequestGuards.hostAllowed("localhost:25599", PORT));
        assertTrue(RequestGuards.hostAllowed("LOCALHOST:25599", PORT)); // case-insensitive
        assertTrue(RequestGuards.hostAllowed("[::1]:25599", PORT));
        assertTrue(RequestGuards.hostAllowed("  127.0.0.1:25599  ", PORT)); // trimmed
    }

    @Test
    void hostAllowed_rejectsWrongPort() {
        // Regression: the guard must validate the ACTUAL bound port, not a different one.
        assertFalse(RequestGuards.hostAllowed("127.0.0.1:25600", PORT));
    }

    @Test
    void hostAllowed_rejectsNonLoopbackHost() {
        assertFalse(RequestGuards.hostAllowed("evil.com:25599", PORT));
        assertFalse(RequestGuards.hostAllowed("192.168.1.5:25599", PORT));
    }

    @Test
    void hostAllowed_rejectsMissingPortBadPortMalformedAndNull() {
        assertFalse(RequestGuards.hostAllowed("127.0.0.1", PORT)); // no port
        assertFalse(RequestGuards.hostAllowed("127.0.0.1:abc", PORT)); // non-numeric port
        assertFalse(RequestGuards.hostAllowed("[::1", PORT)); // malformed IPv6
        assertFalse(RequestGuards.hostAllowed(null, PORT));
        assertFalse(RequestGuards.hostAllowed("", PORT));
    }

    @Test
    void originAllowed_localhostHttpAndHttps() {
        assertTrue(RequestGuards.originAllowed("http://127.0.0.1:25599", PORT));
        assertTrue(RequestGuards.originAllowed("https://localhost:25599", PORT));
        assertTrue(RequestGuards.originAllowed("http://[::1]:25599", PORT));
    }

    @Test
    void originAllowed_rejectsForeignSchemelessWrongPortAndNull() {
        assertFalse(RequestGuards.originAllowed("http://evil.com:25599", PORT));
        assertFalse(RequestGuards.originAllowed("http://127.0.0.1:25600", PORT)); // wrong port
        assertFalse(RequestGuards.originAllowed("127.0.0.1:25599", PORT)); // no scheme
        assertFalse(RequestGuards.originAllowed("ftp://127.0.0.1:25599", PORT)); // bad scheme
        assertFalse(RequestGuards.originAllowed("null", PORT)); // sandboxed-iframe origin
        assertFalse(RequestGuards.originAllowed(null, PORT));
    }

    @Test
    void secFetchSite_blocksCrossAndSameSite() {
        assertTrue(RequestGuards.isBlockedSecFetchSite("cross-site"));
        assertTrue(RequestGuards.isBlockedSecFetchSite("same-site"));
        assertTrue(RequestGuards.isBlockedSecFetchSite("  Cross-Site ")); // trim + case
    }

    @Test
    void secFetchSite_allowsSameOriginNoneAndAbsent() {
        assertFalse(RequestGuards.isBlockedSecFetchSite("same-origin"));
        assertFalse(RequestGuards.isBlockedSecFetchSite("none"));
        assertFalse(RequestGuards.isBlockedSecFetchSite(null));
    }

    @Test
    void constantTimeEquals_matchesDiffersAndIsLengthIndependent() {
        assertTrue(RequestGuards.constantTimeEquals("s3cr3t-token", "s3cr3t-token"));
        assertFalse(RequestGuards.constantTimeEquals("s3cr3t-token", "wrong-token!"));
        assertFalse(RequestGuards.constantTimeEquals("short", "a-much-longer-value")); // differing length, no leak/throw
        assertTrue(RequestGuards.constantTimeEquals("", ""));
    }
}
