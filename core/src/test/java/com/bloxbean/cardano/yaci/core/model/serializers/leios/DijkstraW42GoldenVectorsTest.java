package com.bloxbean.cardano.yaci.core.model.serializers.leios;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.MajorType;
import com.bloxbean.cardano.yaci.core.model.*;
import com.bloxbean.cardano.yaci.core.model.leios.LeiosEndorserBlockRef;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockHeaderSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.DijkstraBlockBodySerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.util.CborSlice;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden vectors from the Haskell node that Musashi {@code rc-2026w42} runs (cardano-ledger {@code 9df9f98c},
 * ouroboros-consensus {@code 840e789b}; see {@code src/test/resources/leios/haskell-w42/README.md}). The expected
 * values were read from the same bytes with an independent CBOR decoder.
 */
class DijkstraW42GoldenVectorsTest {
    private static final String TX_ID = "31bd401ec357e7b2754bcd83e4648f784de5ead4bc90ccf8eab1b4252f3c5c0f";
    private static final String SUB_TX_ID = "80c3560e1360f12a62856de7b985d4d44882a1796c47512dcfe533d5ff5dba4c";
    private static final String SCRIPT = "484701000022220011";

    @Test
    void ledgerExampleTransactionWithPlutusV4Witnesses() throws Exception {
        //As LeiosFetch serves it from w42 on: block_transaction [body, witnesses, aux, is_valid]
        Block block = asEndorserBlockTx(blockTransaction(resource("dijkstra-golden-tx.cbor")));
        TransactionBody tx = block.getTransactionBodies().get(0);
        Witnesses witnesses = block.getTransactionWitness().get(0);

        assertThat(tx.getTxHash()).isEqualTo(TX_ID);
        assertThat(tx.getSubTransactions()).hasSize(1);
        assertThat(tx.getGuards()).hasSize(3);
        assertThat(block.getInvalidTransactions()).isEmpty();

        //Witness set key 8 (new in w42): Plutus V4 scripts, next to V3 (key 7)
        assertThat(witnesses.getPlutusV4Scripts()).hasSize(1);
        assertThat(witnesses.getPlutusV4Scripts().get(0).getType()).isEqualTo(PlutusScriptType.PlutusScriptV4);
        assertThat(witnesses.getPlutusV4Scripts().get(0).getContent()).isEqualTo(SCRIPT);
        assertThat(witnesses.getPlutusV3Scripts()).extracting(PlutusScript::getContent).containsExactly(SCRIPT);

        SubTransaction subTx = tx.getSubTransactions().get(0);
        assertThat(subTx.getBody().getTxHash()).isEqualTo(SUB_TX_ID);
        assertThat(subTx.getWitnesses().getPlutusV4Scripts()).extracting(PlutusScript::getContent)
                .containsExactly(SCRIPT);
    }

    @Test
    void witnessesWithoutKey8HaveNoPlutusV4Scripts() throws Exception {
        //The w40a vector has no key 8: the field stays null, so pre-Dijkstra JSON output is unchanged
        byte[] w40a;
        try (InputStream in = getClass().getResourceAsStream("/leios/haskell/dijkstra-golden-tx.cbor")) {
            w40a = in.readAllBytes();
        }
        Witnesses witnesses = asEndorserBlockTx(blockTransaction(w40a)).getTransactionWitness().get(0);

        assertThat(witnesses.getPlutusV4Scripts()).isNull();
        assertThat(witnesses.getPlutusV3Scripts()).hasSize(1);
    }

    @Test
    void consensusGenTx() throws Exception {
        //[7, #6.24(bytes .cbor tx)]: a minimal mempool transaction; its id is the GenTxId golden 3da9a3b3…
        Array genTx = (Array) CborSerializationUtil.deserializeOne(resource("dijkstra-golden-gentx.cbor"));
        assertThat(CborSerializationUtil.toInt(genTx.getDataItems().get(0))).isEqualTo(7);
        byte[] tx = ((ByteString) genTx.getDataItems().get(1)).getBytes();

        TransactionBody body = asEndorserBlockTx(blockTransaction(tx)).getTransactionBodies().get(0);

        assertThat(body.getTxHash()).isEqualTo("3da9a3b38dce0e87ca4a88f3328caac7a970cf9ac40424cde2b675768d11f11b");
        assertThat(body.getInputs()).isEmpty();
        assertThat(body.getOutputs()).isEmpty();
    }

    @Test
    void consensusHeader() throws Exception {
        //12-field Dijkstra header body; index 9 is header_version_info [2, 0], index 11 eb_references_announcement
        HeaderBody headerBody = BlockHeaderSerializer.INSTANCE.getBlockHeaderFromHeaderArray(
                (Array) CborSerializationUtil.deserializeOne(resource("dijkstra-golden-header.cbor"))).getHeaderBody();

        assertThat(headerBody.getBlockNumber()).isEqualTo(3);
        assertThat(headerBody.getSlot()).isEqualTo(9);
        assertThat(headerBody.getBlockBodySize()).isEqualTo(2345);
        assertThat(headerBody.getProtocolVersion().get_1()).isEqualTo(2);
        assertThat(headerBody.getProtocolVersion().get_2()).isZero();
        assertThat(headerBody.getLeiosCertified()).isFalse();
        assertThat(headerBody.getLeiosAnnouncement().getEbHash())
                .isEqualTo("1ba8a78ded0243004b711e07ac9c0b165c295abfa23de51563b79ac99ec0287c");
        assertThat(headerBody.getLeiosAnnouncement().getEbSize()).isEqualTo(123);
    }

    /** mempool [body, witnesses, aux] -> block_transaction [body, witnesses, aux, is_valid = true] */
    private static byte[] blockTransaction(byte[] mempoolTx) throws Exception {
        List<CborSlice> parts = CborSlice.of(mempoolTx).items(MajorType.ARRAY);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x84);
        for (CborSlice part : parts) out.writeBytes(part.bytes());
        out.write(0xf5);
        return out.toByteArray();
    }

    /** Parse a transaction the way Yaci reads an endorser block transaction, into live w42 block #10027. */
    private static Block asEndorserBlockTx(byte[] tx) throws Exception {
        Block certifying = BlockSerializer.INSTANCE.deserialize(HexUtil.decodeHexString(Files.readString(Path.of(
                DijkstraW42GoldenVectorsTest.class.getResource("/leios/w42/block-10027.hex").toURI())).trim()));
        return DijkstraBlockBodySerializer.INSTANCE.mergeEndorserBlockTransactions(certifying,
                LeiosEndorserBlockRef.builder().build(), List.of(tx));
    }

    private static byte[] resource(String name) throws Exception {
        try (InputStream in = DijkstraW42GoldenVectorsTest.class.getResourceAsStream("/leios/haskell-w42/" + name)) {
            return in.readAllBytes();
        }
    }
}
