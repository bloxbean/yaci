package com.bloxbean.cardano.yaci.core.model.serializers;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.spec.Era;
import com.bloxbean.cardano.yaci.core.model.Credential;
import com.bloxbean.cardano.yaci.core.model.ProtocolParamUpdate;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredType;
import com.bloxbean.cardano.yaci.core.model.Update;
import com.bloxbean.cardano.yaci.core.model.governance.ProposalProcedure;
import com.bloxbean.cardano.yaci.core.model.governance.actions.HardForkInitiationAction;
import com.bloxbean.cardano.yaci.core.model.governance.actions.UpdateCommittee;
import com.bloxbean.cardano.yaci.core.model.serializers.governance.GovActionSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.governance.ProposalProcedureSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.governance.VotingProceduresSerializer;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.core.types.UnitInterval;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /**
     * A committee member's term ends at an epoch, a Word64 in the ledger: its 64 bits are kept, so ratification sees
     * the epoch transaction validation saw. Narrowed to an int, 2^32 + 1 read as 1, within any maximum term.
     */
    @Test
    void committeeTermEpochsKeepTheir64Bits() throws Exception {
        String key = "8200" + H28;
        String script = "8201" + H28;
        byte[] action = bytes("85" + "04" + "f6" + "80"
                + "a2" + key + "1b0000000100000001" + script + "1bffffffffffffffff" + "d81e820102");

        UpdateCommittee read = (UpdateCommittee) GovActionSerializer.INSTANCE.deserialize(action);
        assertThat(read.getNewMembersAndTerms().values()).containsExactly((1L << 32) + 1, -1L);

        UpdateCommittee reread = (UpdateCommittee) GovActionSerializer.INSTANCE.deserialize(
                GovActionSerializer.INSTANCE.serialize(read));
        assertThat(reread.getNewMembersAndTerms()).isEqualTo(read.getNewMembersAndTerms());

        // JSON has the unsigned numbers, and reads back to the same 64 bits (keys read as Yaci Store reads them)
        ObjectMapper json = new ObjectMapper().registerModule(new SimpleModule().addKeyDeserializer(Credential.class,
                new KeyDeserializer() {
                    @Override
                    public Object deserializeKey(String key, DeserializationContext context) {
                        Matcher m = Pattern.compile("Credential\\(type=(\\w+), hash=(\\w+)\\)").matcher(key);
                        assertThat(m.matches()).as(key).isTrue();
                        return new Credential(StakeCredType.valueOf(m.group(1)), m.group(2));
                    }
                }));
        ObjectNode tree = json.valueToTree(read);
        ObjectNode terms = (ObjectNode) tree.get("newMembersAndTerms");
        assertThat(terms.properties()).extracting(e -> e.getValue().bigIntegerValue())
                .containsExactly(BigInteger.valueOf((1L << 32) + 1), new BigInteger("18446744073709551615"));
        assertThat(json.treeToValue(tree, UpdateCommittee.class).getNewMembersAndTerms())
                .isEqualTo(read.getNewMembersAndTerms());

        // JSON written with the signed value still reads the same; a value past 64 bits is refused
        String last = terms.properties().stream().reduce((a, b) -> b).orElseThrow().getKey();
        terms.put(last, -1L);
        assertThat(json.treeToValue(tree, UpdateCommittee.class).getNewMembersAndTerms())
                .isEqualTo(read.getNewMembersAndTerms());
        terms.put(last, BigInteger.ONE.shiftLeft(64));
        assertThatThrownBy(() -> json.treeToValue(tree, UpdateCommittee.class))
                .isInstanceOf(JsonProcessingException.class);
    }

    /** Kept: bytes read with no tree still reject what decoding them to a tree rejects, as the block path does. */
    @Test
    void bytesRejectWhatATreeRejects() {
        // a zero denominator: both CCL readers refuse it (a bignum part is read since CCL 0.8.0-pre8, #753, as
        // cardano-ledger's decodeInteger reads it; see bignumPartsOfARationalAreRead)
        String badRational = "d81e82" + "01" + "00";
        assertThatThrownBy(() -> parameters("a1" + "0c" + badRational)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> GovActionSerializer.INSTANCE.deserialize(bytes("d81e" + "8106")))
                .isInstanceOf(RuntimeException.class);
    }

    /** CCL 0.8.0-pre8 (#753): a rational's part may be a bignum (tag 2 or 3), kept as read. */
    @Test
    void bignumPartsOfARationalAreRead() {
        UnitInterval d = parameters("a1" + "0c" + "d81e82" + "c24101" + "01").getDecentralisationParam();
        assertThat(d.getNumerator()).isEqualTo(BigInteger.ONE);
        assertThat(d.getDenominator()).isEqualTo(BigInteger.ONE);
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
