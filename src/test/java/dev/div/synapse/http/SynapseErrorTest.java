package dev.div.synapse.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The wire error codes/status mapping is part of the public contract (manifest, clients). */
class SynapseErrorTest {

    @Test
    void codeIsTheEnumConstantName() {
        assertEquals("FORBIDDEN", SynapseError.FORBIDDEN.code());
        assertEquals("UNAUTHORIZED", SynapseError.UNAUTHORIZED.code());
        assertEquals("NOT_IN_WORLD", SynapseError.NOT_IN_WORLD.code());
    }

    @Test
    void httpStatusesAreStable() {
        assertEquals(401, SynapseError.UNAUTHORIZED.httpStatus);
        assertEquals(403, SynapseError.FORBIDDEN.httpStatus);
        assertEquals(400, SynapseError.BAD_REQUEST.httpStatus);
        assertEquals(409, SynapseError.NOT_IN_WORLD.httpStatus);
        assertEquals(504, SynapseError.TIMEOUT.httpStatus);
        assertEquals(500, SynapseError.INTERNAL.httpStatus);
    }
}
