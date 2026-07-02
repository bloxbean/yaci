package com.bloxbean.cardano.yaci.core.protocol.leios;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LeiosPointTest {

    @Test
    void protectsHashFromMutation() {
        byte[] hash = new byte[LeiosPoint.EB_HASH_LENGTH];
        hash[0] = 1;
        LeiosPoint point = new LeiosPoint(10, hash);

        hash[0] = 2;
        byte[] returnedHash = point.getEbHash();
        returnedHash[0] = 3;

        assertEquals(1, point.getEbHash()[0]);
    }

    @Test
    void validatesSlotAndHashLength() {
        assertThrows(IllegalArgumentException.class,
                () -> new LeiosPoint(-1, new byte[LeiosPoint.EB_HASH_LENGTH]));
        assertThrows(IllegalArgumentException.class,
                () -> new LeiosPoint(0, new byte[LeiosPoint.EB_HASH_LENGTH - 1]));
    }

    @Test
    void comparesBySlotAndHash() {
        byte[] hash = new byte[LeiosPoint.EB_HASH_LENGTH];
        hash[0] = 1;

        assertEquals(new LeiosPoint(1, hash), new LeiosPoint(1, hash));
        assertArrayEquals(hash, new LeiosPoint(1, hash).getEbHash());
        assertNotEquals(new LeiosPoint(1, hash), new LeiosPoint(2, hash));
    }
}
