package com.bloxbean.cardano.yaci.core.protocol.leios;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeiosTxBitmapTest {

    @Test
    void buildsFirstNUsingMsbFirstConvention() {
        assertTrue(LeiosTxBitmap.firstN(0).isEmpty());
        assertEquals(Map.of(0, Long.MIN_VALUE), LeiosTxBitmap.firstN(1).getWindows());
        assertEquals(Map.of(0, -1L), LeiosTxBitmap.firstN(64).getWindows());
        assertEquals(Map.of(0, -1L, 1, Long.MIN_VALUE), LeiosTxBitmap.firstN(65).getWindows());
    }

    @Test
    void buildsFromIndicesUsingSixtyFourTxWindows() {
        LeiosTxBitmap bitmap = LeiosTxBitmap.fromIndices(0, 63, 64, 130);

        assertEquals(Long.MIN_VALUE | 1L, bitmap.getMask(0));
        assertEquals(Long.MIN_VALUE, bitmap.getMask(1));
        assertEquals(1L << 61, bitmap.getMask(2));
    }

    @Test
    void sortsWindowsDeterministically() {
        LeiosTxBitmap bitmap = LeiosTxBitmap.fromIndices(List.of(130, 0, 64));

        assertEquals(List.of(0, 1, 2), List.copyOf(bitmap.getWindows().keySet()));
    }

    @Test
    void validatesIndicesAndWindows() {
        assertThrows(IllegalArgumentException.class, () -> LeiosTxBitmap.firstN(-1));
        assertThrows(IllegalArgumentException.class, () -> LeiosTxBitmap.fromIndices(-1));
        assertThrows(IllegalArgumentException.class,
                () -> LeiosTxBitmap.fromIndices((LeiosTxBitmap.MAX_WINDOW_INDEX + 1) * 64));
        assertThrows(UnsupportedOperationException.class,
                () -> LeiosTxBitmap.firstN(1).getWindows().put(2, 3L));
    }
}
