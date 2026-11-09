package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.MajorType;
import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.yaci.core.model.AuxData;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.serializers.util.CborSlice;
import com.bloxbean.cardano.yaci.core.util.CborLoader;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * metadataCbor keeps the on-chain encoding: map key order and duplicate keys are not changed.
 * Expected sizes are of each label's on-chain CBOR as a one-entry map {label: value}.
 */
class MetadataSourceCborTest {

    @Test
    void blockKeepsMetadataKeyOrder() throws Exception {
        // Preprod tx 06967cfc…, label 55534472: "unlock_time" comes before "destination" on chain.
        for (boolean fullTx : new boolean[]{false, true}) {
            AuxData aux = auxData("block/preprod5013964.txt",
                    "06967cfc62426d3dc23c1ba3b819db0359833cc0e7b741c368d449cc328af481", fullTx);
            assertThat(aux.getMetadataCbor()).startsWith("a11a034f6388a26b756e6c6f636b5f74696d65");
            assertThat(labelMapSize(aux, 55534472)).isEqualTo(231);
        }
    }

    @Test
    void blockKeepsDuplicateMetadataKeys() throws Exception {
        // Preprod tx 81335676…, label 721 repeats "mediaType": "image/png" (20 bytes) on chain.
        for (boolean fullTx : new boolean[]{false, true}) {
            AuxData aux = auxData("block/preprod4221281.txt",
                    "81335676dd9190fa6bf8b3dedcca2921f145f2034e423fbd54c868cdd884a649", fullTx);
            assertThat(labelMapSize(aux, 721)).isEqualTo(1598);
            assertThat(aux.getMetadataJson()).isNotNull();
        }
    }

    @Test
    void auxDataBytesKeepMetadataEncodingInEveryFormat() {
        // {1: {"b": 1, "a": 2, "a": 3}}: unsorted keys and a duplicate key.
        String metadata = "a101a3616201616102616103";
        for (String aux : new String[]{metadata, "82" + metadata + "80", "d90103a100" + metadata}) {
            AuxData parsed = AuxDataSerializer.INSTANCE.deserialize(HexUtil.decodeHexString(aux));
            assertThat(parsed.getMetadataCbor()).isEqualTo(metadata);
            assertThat(parsed.getMetadataJson()).isNotNull();
            assertThat(parsed.getMetadataParseError()).isNull();
        }
    }

    private static AuxData auxData(String file, String txHash, boolean fullTx) {
        boolean previous = YaciConfig.INSTANCE.isReturnFullTxCbor();
        YaciConfig.INSTANCE.setReturnFullTxCbor(fullTx);
        try {
            Block block = BlockSerializer.INSTANCE.deserialize(CborLoader.getHexBytes(file));
            List<TransactionBody> bodies = block.getTransactionBodies();
            for (int i = 0; i < bodies.size(); i++) {
                if (bodies.get(i).getTxHash().equals(txHash)) return block.getAuxiliaryDataMap().get(i);
            }
            throw new AssertionError("tx not found: " + txHash);
        } finally {
            YaciConfig.INSTANCE.setReturnFullTxCbor(previous);
        }
    }

    /** Size of {label: value} as one-entry map: the a1 header byte, then the key and value bytes. */
    private static int labelMapSize(AuxData aux, long label) throws Exception {
        List<CborSlice> entries = CborSlice.of(HexUtil.decodeHexString(aux.getMetadataCbor())).items(MajorType.MAP);
        for (int i = 0; i < entries.size(); i += 2) {
            if (DataItemIsolation.unsigned(entries.get(i)) == label) {
                return 1 + entries.get(i).bytes().length + entries.get(i + 1).bytes().length;
            }
        }
        throw new AssertionError("label not found: " + label);
    }
}
