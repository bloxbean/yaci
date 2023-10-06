package com.bloxbean.cardano.yaci.core.model.serializers;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Disabled
class BlockSerializerTest {

    @Test
    void deserializeDI() {
        String cborHex = "";

         Block block = BlockSerializer.INSTANCE.deserialize(HexUtil.decodeHexString(cborHex));

        System.out.println(block);
    }
}
