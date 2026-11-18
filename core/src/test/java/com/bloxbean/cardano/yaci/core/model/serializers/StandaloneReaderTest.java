package com.bloxbean.cardano.yaci.core.model.serializers;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.spec.Era;
import com.bloxbean.cardano.yaci.core.model.AuxData;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.Witnesses;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The public byte readers, called on their own rather than by {@link BlockSerializer}, which hands them exact bytes
 * and the block's era.
 */
class StandaloneReaderTest {
    /** {0: [], 1: []}: a body with no inputs and no outputs */
    private static final String BODY = "a200800180";
    /** {5: [h'01020304']}: one Plutus V4 witness script */
    private static final String V4_WITNESSES = "a108814401020304";
    /** 259({5: [h'01020304']}): aux data with one Plutus V4 script */
    private static final String V4_AUX = "d90103a105814401020304";

    @Test
    void aBodyIdIsTheHashOfExactlyTheBody() {
        byte[] body = HexUtil.decodeHexString(BODY);
        TransactionBody read = TransactionBodySerializer.INSTANCE.read(body, Era.Conway);
        assertThat(read.getTxHash()).isEqualTo(HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(body)));
        assertThat(read.getInputs()).isEmpty();
        assertThat(read.getOutputs()).isEmpty();
    }

    @Test
    void bytesAfterABodyAreRejectedNotHashedIntoItsId() {
        for (String trailing : new String[]{"00", "a0", "f6"}) {
            byte[] bytes = HexUtil.decodeHexString(BODY + trailing);
            assertThatThrownBy(() -> TransactionBodySerializer.INSTANCE.read(bytes, Era.Conway))
                    .as(trailing).isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    @SuppressWarnings("removal")
    void theDeprecatedOverloadStillHashesWhatItIsGiven() {
        // TransactionBodyMappingTest.aBodyIsReadFromItsBytes pins this too; kept here beside the strict reader.
        byte[] trailing = HexUtil.decodeHexString(BODY + "00");
        TransactionBody read = TransactionBodySerializer.INSTANCE.deserializeDI(null, trailing);
        assertThat(read.getTxHash()).isEqualTo(HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(trailing)));
    }

    @Test
    void witnessesAreReadInTheEraGiven() {
        byte[] bytes = HexUtil.decodeHexString(V4_WITNESSES);
        Witnesses dijkstra = WitnessesSerializer.INSTANCE.deserialize(bytes, Era.Dijkstra);
        assertThat(dijkstra.getPlutusV4Scripts()).hasSize(1);
        assertThat(dijkstra.getPlutusV4Scripts().get(0).getContent()).isEqualTo("4401020304");
        assertThat(dijkstra.getPlutusV4Scripts().get(0).getScriptHash()).isNotNull();

        // key 5 is not a field before Dijkstra; the single-argument reader stays Conway
        assertThat(WitnessesSerializer.INSTANCE.deserialize(bytes, Era.Conway).getPlutusV4Scripts()).isEmpty();
        assertThat(WitnessesSerializer.INSTANCE.deserialize(bytes).getPlutusV4Scripts()).isEmpty();
        assertThat(WitnessesSerializer.INSTANCE.deserialize(new byte[0], Era.Dijkstra)).isNull();
        assertThatThrownBy(() -> WitnessesSerializer.INSTANCE.deserialize(bytes, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void auxDataIsReadInTheEraGiven() {
        byte[] bytes = HexUtil.decodeHexString(V4_AUX);
        AuxData dijkstra = AuxDataSerializer.INSTANCE.deserialize(bytes, Era.Dijkstra);
        assertThat(dijkstra.getPlutusV4Scripts()).hasSize(1);
        // as in 0.4.x, an aux data script's content is its bytes, a witness script's is its CBOR
        assertThat(dijkstra.getPlutusV4Scripts().get(0).getContent()).isEqualTo("01020304");
        assertThat(dijkstra.getPlutusV4Scripts().get(0).getScriptHash()).isNotNull();

        assertThat(AuxDataSerializer.INSTANCE.deserialize(bytes, Era.Conway).getPlutusV4Scripts()).isNull();
        assertThat(AuxDataSerializer.INSTANCE.deserialize(bytes).getPlutusV4Scripts()).isNull();
        assertThat(AuxDataSerializer.INSTANCE.deserialize(new byte[0], Era.Dijkstra)).isNull();
        assertThatThrownBy(() -> AuxDataSerializer.INSTANCE.deserialize(bytes, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void witnessesAndAuxDataRejectTrailingBytes() {
        assertThatThrownBy(() -> WitnessesSerializer.INSTANCE.deserialize(HexUtil.decodeHexString(V4_WITNESSES + "00"),
                Era.Dijkstra)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> AuxDataSerializer.INSTANCE.deserialize(HexUtil.decodeHexString(V4_AUX + "00"),
                Era.Dijkstra)).isInstanceOf(RuntimeException.class);
    }
}
