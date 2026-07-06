package com.bloxbean.cardano.yaci.core.model.serializers;

import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.serializers.util.AuxDataExtractor;
import com.bloxbean.cardano.yaci.core.util.CborLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BlockSerializerRawSegmentTest {

    @AfterEach
    void tearDown() {
        YaciConfig.INSTANCE.setReturnFullTxCbor(false);
    }

    @Test
    void deserialize_doesNotSetAuxCborWhenAuxiliaryDataHashMismatches() {
        byte[] blockBytes = CborLoader.getHexBytes("block/preprod292683.txt");
        byte[] corruptedBlockBytes = corruptAuxDataPayloadByte(blockBytes, 1);
        YaciConfig.INSTANCE.setReturnFullTxCbor(true);

        Block block = BlockSerializer.INSTANCE.deserialize(corruptedBlockBytes);

        assertThat(block.getTransactionBodies().get(1).getAuxiliaryDataHash()).isNotNull();
        assertThat(block.getAuxiliaryDataMap()).containsKey(1);
        assertThat(block.getAuxiliaryDataMap().get(1).getCbor()).isNull();
        assertThat(block.getAuxiliaryDataMap().get(2).getCbor()).isNotNull();
    }

    private byte[] corruptAuxDataPayloadByte(byte[] blockBytes, int txIndex) {
        Map<Integer, byte[]> rawAuxData = AuxDataExtractor.getAuxDataFromBlock(blockBytes);
        byte[] auxBytes = rawAuxData.get(txIndex);
        assertThat(auxBytes).isNotNull();

        int auxStart = indexOf(blockBytes, auxBytes);
        assertThat(auxStart).isGreaterThanOrEqualTo(0);

        byte[] corrupted = Arrays.copyOf(blockBytes, blockBytes.length);
        corrupted[auxStart + auxBytes.length - 1] ^= 0x01;
        return corrupted;
    }

    private int indexOf(byte[] source, byte[] target) {
        for (int sourceIndex = 0; sourceIndex <= source.length - target.length; sourceIndex++) {
            boolean matches = true;
            for (int targetIndex = 0; targetIndex < target.length; targetIndex++) {
                if (source[sourceIndex + targetIndex] != target[targetIndex]) {
                    matches = false;
                    break;
                }
            }

            if (matches) {
                return sourceIndex;
            }
        }

        return -1;
    }
}
