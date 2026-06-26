package dev.div.synapse.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessLevelTest {

    @Test
    void permitsIsRankOrdered() {
        assertTrue(AccessLevel.DEVELOPER.permits(AccessLevel.DEVELOPER));
        assertTrue(AccessLevel.DEVELOPER.permits(AccessLevel.PLAY));
        assertTrue(AccessLevel.DEVELOPER.permits(AccessLevel.OBSERVE));
        assertTrue(AccessLevel.PLAY.permits(AccessLevel.PLAY));
        assertTrue(AccessLevel.PLAY.permits(AccessLevel.OBSERVE));
        assertTrue(AccessLevel.OBSERVE.permits(AccessLevel.OBSERVE));
        assertFalse(AccessLevel.PLAY.permits(AccessLevel.DEVELOPER));
        assertFalse(AccessLevel.OBSERVE.permits(AccessLevel.PLAY));
        assertFalse(AccessLevel.OBSERVE.permits(AccessLevel.DEVELOPER));
    }

    @Test
    void cappedAtLowersToCeiling() {
        // A developer token under a 'play' ceiling is granted only 'play' (developer stays opt-in).
        assertEquals(AccessLevel.PLAY, AccessLevel.DEVELOPER.cappedAt(AccessLevel.PLAY));
        assertEquals(AccessLevel.OBSERVE, AccessLevel.PLAY.cappedAt(AccessLevel.OBSERVE));
        // At or below the ceiling, the token's own level stands.
        assertEquals(AccessLevel.PLAY, AccessLevel.PLAY.cappedAt(AccessLevel.PLAY));
        assertEquals(AccessLevel.DEVELOPER, AccessLevel.DEVELOPER.cappedAt(AccessLevel.DEVELOPER));
        assertEquals(AccessLevel.OBSERVE, AccessLevel.OBSERVE.cappedAt(AccessLevel.DEVELOPER));
    }

    @Test
    void idIsLowercase() {
        assertEquals("observe", AccessLevel.OBSERVE.id());
        assertEquals("play", AccessLevel.PLAY.id());
        assertEquals("developer", AccessLevel.DEVELOPER.id());
    }
}
