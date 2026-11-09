package com.bloxbean.cardano.yaci.core.model.serializers;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.spec.Era;
import com.bloxbean.cardano.yaci.core.model.ProtocolParamUpdate;
import com.bloxbean.cardano.yaci.core.model.Update;
import com.bloxbean.cardano.yaci.core.model.governance.ProposalProcedure;
import com.bloxbean.cardano.yaci.core.model.governance.actions.HardForkInitiationAction;
import com.bloxbean.cardano.yaci.core.model.serializers.governance.GovActionSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.governance.ProposalProcedureSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.governance.VotingProceduresSerializer;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The update and governance mappers over CCL's views (ADR 0003 D8): the D8 correction of {@code costModelsHash}, and
 * where they differ from the decoders they replaced (I5). Tests named {@code differenceM5} pin what M5 changed on
 * inputs no real chain has; {@link GovernancePinTest} pins what it kept.
 */
class GovernanceMappingTest {
    private static final String H28 = "581c" + "11".repeat(28);
    private static final String H32 = "5820" + "22".repeat(32);
    private static final String ANCHOR = "82" + "6b" + "68747470733a2f2f612e62" + H32;
    private static final String RA = "581de1" + "44".repeat(28);

    private static byte[] bytes(String hex) {
        return HexUtil.decodeHexString(hex);
    }

    private static ProtocolParamUpdate parameters(String ppu) {
        Update update = UpdateSerializer.INSTANCE.deserialize(bytes("82" + "a1" + H28 + ppu + "00"));
        return update.getProtocolParamUpdates().get("11".repeat(28));
    }

    private static ProposalProcedure proposal(String action) {
        return ProposalProcedureSerializer.INSTANCE.deserialize(bytes("84" + "1a05f5e100" + RA + action + ANCHOR));
    }

    /** D8: the hash and each language's cost model are the bytes received, not a canonical re-encoding of them. */
    @Test
    void costModelsAndTheirHashAreTheBytesReceived() {
        String plutusV1 = "83" + "1801" + "02" + "03";            // a cost written in two bytes
        String plutusV2 = "9f" + "04" + "05" + "ff";              // an indefinite array
        String costModels = "a2" + "01" + plutusV2 + "00" + plutusV1; // keys out of canonical order
        ProtocolParamUpdate read = parameters("a1" + "12" + costModels);

        assertThat(read.getCostModelsHash())
                .isEqualTo(HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(bytes(costModels))));
        assertThat(read.getCostModels()).containsExactly(Map.entry(1, plutusV2), Map.entry(0, plutusV1));
    }

    @Test
    void keySeventeenIsTheCoinsPerUtxoInEveryEra() {
        String update = "82" + "a1" + H28 + "a1" + "11" + "1910d7" + "00";
        String body = "a4" + "008001800200" + "06" + update;
        for (Era era : new Era[]{Era.Alonzo, Era.Babbage}) {
            Update read = TransactionBodySerializer.INSTANCE.read(bytes(body), era).getUpdate();
            assertThat(read.getProtocolParamUpdates().get("11".repeat(28)).getAdaPerUtxoByte()).as(era.name())
                    .isEqualTo(BigInteger.valueOf(4311));
        }
    }

    /** Kept: a 32-bit field, the epoch and a hard fork's minor version keep their low 32 bits, as they always have. */
    @Test
    void numbersOfThirtyTwoBitsKeepTheirLowBits() {
        assertThat(parameters("a1" + "02" + "1a80000000").getMaxBlockSize()).isEqualTo(Integer.MIN_VALUE);
        Update update = UpdateSerializer.INSTANCE.deserialize(bytes("82" + "a0" + "1b0000000100000005"));
        assertThat(update.getEpoch()).isEqualTo(5);
        HardForkInitiationAction action = (HardForkInitiationAction) GovActionSerializer.INSTANCE.deserialize(
                bytes("83" + "01" + "f6" + "82" + "0a" + "1a80000000"));
        assertThat(action.getProtocolVersion().get_2()).isEqualTo(Integer.MIN_VALUE);
    }

    /** Kept: bytes read with no tree still reject what decoding them to a tree rejects, as the block path does. */
    @Test
    void bytesRejectWhatATreeRejects() {
        String badRational = "d81e82" + "c24101" + "01";
        assertThatThrownBy(() -> parameters("a1" + "0c" + badRational)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> GovActionSerializer.INSTANCE.deserialize(bytes("d81e" + "8106")))
                .isInstanceOf(RuntimeException.class);
    }

    /** Arrays of a definite length only, as the ledger decodes them below protocol version 12. */
    @Test
    void differenceM5IndefiniteRecordsAreRejected() {
        assertThatThrownBy(() -> UpdateSerializer.INSTANCE.deserialize(bytes("9f" + "a0" + "00" + "ff")))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> ProposalProcedureSerializer.INSTANCE.deserialize(
                bytes("9f" + "1a05f5e100" + RA + "8106" + ANCHOR + "ff"))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> proposal("82" + "03" + "9f" + H32 + "00" + "ff"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> VotingProceduresSerializer.INSTANCE.deserialize(
                bytes("a1" + "8202" + H28 + "a1" + "82" + H32 + "00" + "9f" + "01" + "f6" + "ff")))
                .isInstanceOf(RuntimeException.class);
    }

    /** Records with items the ledger does not decode: too few or too many, an unknown nonce, a null anchor. */
    @Test
    void differenceM5RecordsOfTheWrongShapeAreRejected() {
        assertThatThrownBy(() -> UpdateSerializer.INSTANCE.deserialize(bytes("83" + "a0" + "00" + "00")))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> parameters("a1" + "0e" + "83" + "02" + "00" + "00"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> parameters("a1" + "14" + "81" + "01")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> parameters("a1" + "0d" + "81" + "01")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> parameters("a1" + "0d" + "81" + "02")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> proposal("83" + "00" + "f6" + "a0")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> proposal("82" + "02" + "a0")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> ProposalProcedureSerializer.INSTANCE.deserialize(
                bytes("84" + "1a05f5e100" + RA + "8106" + "f6"))).isInstanceOf(RuntimeException.class);
    }

    /** Numbers the ledger decodes as unsigned, or as a Word16 or Word32, that the old code wrapped or accepted. */
    @Test
    void differenceM5NumbersOutOfTheirRangeAreRejected() {
        assertThatThrownBy(() -> UpdateSerializer.INSTANCE.deserialize(bytes("82" + "a0" + "20")))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> parameters("a1" + "08" + "1a80000000")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> proposal("82" + "03" + "82" + H32 + "1a80000000"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> proposal("81" + "c24106")).isInstanceOf(RuntimeException.class);
    }

    /** A parameter the old code read with an int cast is null when it is no number, as the coins have always been. */
    @Test
    void differenceM5AFeeThatIsNoNumberIsNull() {
        ProtocolParamUpdate read = parameters("a2" + "00" + "6161" + "01" + "182c");
        assertThat(read.getMinFeeA()).isNull();
        assertThat(read.getMinFeeB()).isEqualTo(44);
    }
}
