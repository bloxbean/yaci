package com.bloxbean.cardano.yaci.core.model.serializers;

import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.yaci.core.model.AuxData;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Datum;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.util.CborLoader;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * metadataCbor keeps the on-chain encoding: map key order and duplicate keys are not changed (yaci-store#1215).
 * Expected sizes are of each label's on-chain CBOR as a one-entry map {label: value}. The tests and fixtures come from
 * bloxbean/yaci#243 (against main), read here with CCL's span.
 */
class MetadataSourceCborTest {

    @Test
    void blockKeepsMetadataKeyOrder() {
        // Preprod tx 06967cfc…, label 55534472: "unlock_time" comes before "destination" on chain.
        for (boolean fullTx : new boolean[]{false, true}) {
            AuxData aux = auxData("block/preprod5013964.txt",
                    "06967cfc62426d3dc23c1ba3b819db0359833cc0e7b741c368d449cc328af481", fullTx);
            assertThat(aux.getMetadataCbor()).startsWith("a11a034f6388a26b756e6c6f636b5f74696d65");
            assertThat(labelMapSize(aux, 55534472)).isEqualTo(231);
        }
    }

    @Test
    void blockKeepsDuplicateMetadataKeys() {
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
                if (!bodies.get(i).getTxHash().equals(txHash)) continue;
                AuxData aux = block.getAuxiliaryDataMap().get(i);
                // Both fixture txs use the Shelley form, where auxiliary data is the metadata map itself,
                // so every byte of metadataCbor is checked against the tx body's auxiliary_data_hash.
                assertThat(Datum.cborToHash(HexUtil.decodeHexString(aux.getMetadataCbor())))
                        .isEqualTo(bodies.get(i).getAuxiliaryDataHash());
                return aux;
            }
            throw new AssertionError("tx not found: " + txHash);
        } finally {
            YaciConfig.INSTANCE.setReturnFullTxCbor(previous);
        }
    }

    /** Size of {label: value} as one-entry map: the a1 header byte, then the key and value bytes. */
    private static int labelMapSize(AuxData aux, long label) {
        CborSpan metadata = CborSpan.of(HexUtil.decodeHexString(aux.getMetadataCbor()));
        for (Map.Entry<CborSpan, CborSpan> entry : metadata.entries()) {
            if (entry.getKey().asLong() == label) {
                return 1 + entry.getKey().length() + entry.getValue().length();
            }
        }
        throw new AssertionError("label not found: " + label);
    }
}
