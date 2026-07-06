package com.bloxbean.cardano.yaci.core.model.serializers;

import com.bloxbean.cardano.yaci.core.model.serializers.util.AuxDataExtractor;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuxDataExtractorTest {

    @Test
    void getAuxDataFromBlock_definiteMap() {
        byte[] blockBytes = HexUtil.decodeHexString("820585808080a200a101020182030480");

        Map<Integer, byte[]> auxData = AuxDataExtractor.getAuxDataFromBlock(blockBytes);

        assertThat(auxData).hasSize(2);
        assertThat(HexUtil.encodeHexString(auxData.get(0))).isEqualTo("a10102");
        assertThat(HexUtil.encodeHexString(auxData.get(1))).isEqualTo("820304");
    }

    @Test
    void getAuxDataFromBlock_indefiniteMap() {
        byte[] blockBytes = HexUtil.decodeHexString("820585808080bf00a1010201820304ff80");

        Map<Integer, byte[]> auxData = AuxDataExtractor.getAuxDataFromBlock(blockBytes);

        assertThat(auxData).hasSize(2);
        assertThat(HexUtil.encodeHexString(auxData.get(0))).isEqualTo("a10102");
        assertThat(HexUtil.encodeHexString(auxData.get(1))).isEqualTo("820304");
    }
}
