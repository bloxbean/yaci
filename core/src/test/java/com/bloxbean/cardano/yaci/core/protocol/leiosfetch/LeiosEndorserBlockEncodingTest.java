package com.bloxbean.cardano.yaci.core.protocol.leiosfetch;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosBlock;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosFetchError;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class LeiosEndorserBlockEncodingTest {
    // The consensus codec fixture has one transaction hash of 32 bytes of 0x07 and a size of 100.
    private static final String REFERENCES = "a15820" + "07".repeat(32) + "1864";
    private static final String EB_HASH = "a6792460f88bb7302ae819e4710670316dff26d7e8843bc8e782d27ffa044e0d";

    @Test
    void matchesTheAnnouncedSizeAndHashForBothWireFormats() {
        for (String field : new String[]{REFERENCES, "d8185825" + REFERENCES}) {
            byte[] wire = HexUtil.decodeHexString("8202" + field);
            MsgLeiosBlock block = assertInstanceOf(MsgLeiosBlock.class, LeiosFetchStateBase.deserialize(wire));

            byte[] references = block.getEndorserBlock().getCbor();
            assertEquals(37, references.length);
            assertEquals(EB_HASH, HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(references)));
            assertArrayEquals(wire, block.serialize());
        }
    }

    @Test
    void preservesTheOriginalEncodingOfTheEmbeddedMap() {
        byte[] wire = HexUtil.decodeHexString("8202d8185f41bf41ffff");
        MsgLeiosBlock block = assertInstanceOf(MsgLeiosBlock.class, LeiosFetchStateBase.deserialize(wire));

        assertEquals("bfff", block.getEndorserBlock().toHex());
        assertArrayEquals(wire, block.serialize());
    }

    @Test
    void rejectsAMalformedTag24Field() {
        for (String field : new String[]{"d818a0", "d81840", "d81842a0a0"}) {
            assertInstanceOf(MsgLeiosFetchError.class, LeiosFetchStateBase.deserialize(HexUtil.decodeHexString("8202" + field)));
        }
    }
}
