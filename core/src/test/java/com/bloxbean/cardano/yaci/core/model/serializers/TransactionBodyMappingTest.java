package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.address.util.AddressUtil;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionInput;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yaci.core.model.certs.StakeRegistration;
import com.bloxbean.cardano.yaci.core.util.CborLoader;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.bloxbean.cardano.yaci.core.util.Constants.LOVELACE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bodies, outputs, inputs and withdrawals through the mappers over CCL's reader (ADR 0003 D8): the models they fill,
 * what they keep accepting, and where they differ from the decoders they replaced (I5). Tests named
 * {@code differenceM3} pin what M3 narrowed: an input index or network id of 2^31 or more, which the old code wrapped.
 */
class TransactionBodyMappingTest {
    private static final String H28 = "11".repeat(28);
    private static final String H32 = "22".repeat(32);
    private static final String HASH28 = "581c" + H28;
    private static final String HASH32 = "5820" + H32;
    private static final String RA = "e1" + "33".repeat(28);
    private static final String ACCOUNT = "581d" + RA;
    private static final String ADDR = "581d61" + "44".repeat(28);
    private static final String INPUT = "82" + HASH32 + "05";
    private static final String MIN = "00" + "80" + "01" + "80" + "02" + "00";
    private static final String MULTI_ASSET = "a1" + HASH28 + "a2" + "4161" + "05" + "41ff" + "20";

    private static byte[] bytes(String hex) {
        return HexUtil.decodeHexString(hex);
    }

    private static DataItem item(String hex) {
        return CborSerializationUtil.deserializeOne(bytes(hex));
    }

    private static TransactionBody body(String hex) {
        return TransactionBodySerializer.INSTANCE.deserializeDI(item(hex), bytes(hex));
    }

    private static TransactionOutput output(String hex) {
        return TransactionOutputSerializer.INSTANCE.deserializeDI(item(hex));
    }

    private static String address() throws Exception {
        return AddressUtil.bytesToAddress(bytes(ADDR.substring(4)));
    }

    private static Amount lovelace(long quantity) {
        return Amount.builder().unit(LOVELACE).assetName(LOVELACE).quantity(BigInteger.valueOf(quantity)).build();
    }

    @Test
    void everyFieldFillsItsModel() throws Exception {
        String body = "b4" + "00" + "d90102" + "81" + INPUT + "01" + "81" + "82" + ADDR + "07" + "02" + "1864"
                + "03" + "1a00010000" + "04" + "81" + "82" + "00" + "8200" + HASH28 + "05" + "a1" + ACCOUNT + "07"
                + "07" + HASH32 + "08" + "01" + "09" + MULTI_ASSET + "0b" + HASH32 + "0d" + "81" + INPUT
                + "0e" + "81" + HASH28 + "0f" + "01" + "10" + "82" + ADDR + "06" + "11" + "08" + "12" + "81" + INPUT
                + "13" + "a1" + "8200" + HASH28 + "a1" + "82" + HASH32 + "00" + "82" + "00" + "f6"
                + "14" + "81" + "84" + "05" + ACCOUNT + "8106" + "82" + "6161" + HASH32
                + "15" + "190901" + "16" + "c24101";
        TransactionBody read = body(body);

        TransactionInput input = new TransactionInput(H32, 5);
        assertThat(read.getTxHash()).isEqualTo(HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(bytes(body))));
        assertThat(read.getInputs()).isInstanceOf(LinkedHashSet.class).containsExactly(input);
        assertThat(read.getOutputs()).containsExactly(TransactionOutput.builder().address(address())
                .amounts(List.of(lovelace(7))).build());
        assertThat(read.getFee()).isEqualTo(100);
        assertThat(read.getTtl()).isEqualTo(65536);
        assertThat(read.getCertificates()).singleElement().isInstanceOf(StakeRegistration.class);
        assertThat(read.getWithdrawals()).containsExactly(Map.entry(RA, BigInteger.valueOf(7)));
        assertThat(read.getAuxiliaryDataHash()).isEqualTo(H32);
        assertThat(read.getValidityIntervalStart()).isEqualTo(1);
        assertThat(read.getMint()).extracting(Amount::getQuantity)
                .containsExactly(BigInteger.valueOf(5), BigInteger.valueOf(-1));
        assertThat(read.getScriptDataHash()).isEqualTo(H32);
        assertThat(read.getCollateralInputs()).isExactlyInstanceOf(HashSet.class).containsExactly(input);
        assertThat(read.getRequiredSigners()).isExactlyInstanceOf(HashSet.class).containsExactly(H28);
        assertThat(read.getNetowrkId()).isEqualTo(1);
        assertThat(read.getCollateralReturn().getAmounts()).containsExactly(lovelace(6));
        assertThat(read.getTotalCollateral()).isEqualTo(8);
        assertThat(read.getReferenceInputs()).isInstanceOf(LinkedHashSet.class).containsExactly(input);
        assertThat(read.getVotingProcedures().getVoting()).hasSize(1);
        assertThat(read.getProposalProcedures()).singleElement()
                .satisfies(proposal -> assertThat(proposal.getDeposit()).isEqualTo(5));
        assertThat(read.getCurrentTreasuryValue()).isEqualTo(2305);
        assertThat(read.getDonation()).isEqualTo(1);
    }

    @Test
    void aMinimalBodyLeavesItsFieldsAtTheirDefaults() {
        TransactionBody read = body("a3" + MIN);
        assertThat(read.getInputs()).isEmpty();
        assertThat(read.getOutputs()).isEmpty();
        assertThat(read.getCertificates()).isEmpty();
        assertThat(read.getMint()).isEmpty();
        assertThat(read.getWithdrawals()).isNull();
        assertThat(read.getCollateralInputs()).isNull();
        assertThat(read.getRequiredSigners()).isNull();
        assertThat(read.getReferenceInputs()).isNull();
        assertThat(read.getProposalProcedures()).isNull();
        assertThat(read.getCollateralReturn()).isNull();
        assertThat(read.getNetowrkId()).isZero();
    }

    @Test
    void anEmptyFieldKeepsItsOldReading() {
        TransactionBody read = body("a7" + MIN + "04" + "80" + "05" + "a0" + "09" + "a0" + "0d" + "80");
        assertThat(read.getCertificates()).isEmpty();
        assertThat(read.getWithdrawals()).isNull();
        assertThat(read.getMint()).isEmpty();
        assertThat(read.getCollateralInputs()).isEmpty();
    }

    @Test
    void feeIsOptionalAndInputsAndOutputsAreNot() {
        assertThat(body("a2" + "0080" + "0180").getFee()).isNull();
        assertThatThrownBy(() -> body("a2" + "0080" + "0200")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> body("a2" + "0180" + "0200")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void aRepeatedKeyMeansItsLastValue() {
        assertThat(body("a4" + MIN + "0205").getFee()).isEqualTo(5);
        assertThat(body("a4" + MIN + "1802" + "05").getFee()).isEqualTo(5);
        // a tagged key is another key
        assertThat(body("a4" + MIN + "c102" + "05").getFee()).isZero();

        TransactionOutput repeated = output("a3" + "00" + ADDR + "0101" + "01" + "02");
        assertThat(repeated.getAmounts()).containsExactly(lovelace(2));
    }

    @Test
    void aBodyIsReadFromItsBytes() {
        String hex = "a3" + MIN;
        TransactionBody read = TransactionBodySerializer.INSTANCE.deserializeDI(item("a3" + "0080" + "0180" + "0205"),
                bytes(hex));
        assertThat(read.getFee()).isZero();
        // bytes after the body are hashed and not read, as the old code took them
        byte[] trailing = bytes(hex + "00");
        read = TransactionBodySerializer.INSTANCE.deserializeDI(item(hex), trailing);
        assertThat(read.getTxHash()).isEqualTo(HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(trailing)));
    }

    @Test
    void whatDecodingATreeRejectsIsRejectedInAnyField() {
        for (String field : List.of("d81e" + "00", "d826" + "8100"))
            assertThatThrownBy(() -> TransactionBodySerializer.INSTANCE.deserializeDI(null, bytes("a4" + MIN + "18ff"
                    + field))).isInstanceOf(RuntimeException.class);
    }

    @Test
    void tagsOnARecordAreLookedThrough() {
        assertThat(body("d818" + "a3" + MIN).getFee()).isZero();
        assertThat(body("a3" + "00" + "d90102" + "9f" + "d818" + INPUT + "ff" + "01" + "d81880" + "02" + "c100")
                .getInputs()).containsExactly(new TransactionInput(H32, 5));
    }

    @Test
    void aValueArrayOfAtLeastTwoItemsIsACoinAndAssetsInAnyForm() {
        for (String value : List.of("82" + "07" + MULTI_ASSET, "9f" + "07" + MULTI_ASSET + "ff",
                "83" + "07" + MULTI_ASSET + "00")) {
            List<Amount> amounts = output("82" + ADDR + value).getAmounts();
            assertThat(amounts).hasSize(3);
            assertThat(amounts.get(0)).isEqualTo(lovelace(7));
        }
        for (String value : List.of("80", "81" + "07", "9f" + "07" + "ff", "82" + "07" + "00"))
            assertThatThrownBy(() -> output("82" + ADDR + value)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void anAmountKeepsItsNumberReading() {
        // what is no number is a null quantity; an untagged byte string fails
        assertThat(output("82" + ADDR + "82" + "6161" + "a0").getAmounts().get(0).getQuantity()).isNull();
        assertThat(output("82" + ADDR + "82" + "07" + "a1" + HASH28 + "a1" + "4161" + "6161").getAmounts().get(1)
                .getQuantity()).isNull();
        assertThatThrownBy(() -> output("82" + ADDR + "82" + "4101" + "a0")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> output("82" + ADDR + "82" + "07" + "a1" + HASH28 + "a1" + "4161" + "4101"))
                .isInstanceOf(RuntimeException.class);
        assertThat(output("82" + ADDR + "20").getAmounts().get(0).getQuantity()).isEqualTo(BigInteger.ONE.negate());
        // a value of another kind has no amounts
        assertThat(output("82" + ADDR + "6161").getAmounts()).isEmpty();
        assertThat(output("82" + ADDR + "a0").getAmounts()).isEmpty();
        // a body's treasury and donation read the same way
        assertThat(body("a4" + MIN + "15" + "6161").getCurrentTreasuryValue()).isNull();
        assertThat(body("a4" + MIN + "16" + "20").getDonation()).isEqualTo(BigInteger.ONE.negate());
    }

    @Test
    void anAssetNameIsTextWhenItIsUtf8() {
        List<Amount> amounts = output("82" + ADDR + "82" + "07" + MULTI_ASSET).getAmounts();
        Amount text = amounts.get(1);
        assertThat(text.getPolicyId()).isEqualTo(H28);
        assertThat(text.getUnit()).isEqualTo(H28 + ".61");
        assertThat(text.getAssetName()).isEqualTo("a");
        assertThat(text.getAssetNameBytes()).isEqualTo(new byte[]{0x61});
        assertThat(amounts.get(2).getUnit()).isEqualTo(H28 + ".ff");
        assertThat(amounts.get(2).getAssetName()).startsWith("asset1");
    }

    @Test
    void aMapOutputMayLackItsAddressAndNotItsValue() {
        assertThat(output("a1" + "01" + "07").getAddress()).isNull();
        assertThatThrownBy(() -> output("a1" + "00" + ADDR)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void anOutputIsAnArrayOfTwoOrThreeOrAMap() {
        for (String bad : List.of("00", "81" + ADDR, "84" + ADDR + "00" + HASH32 + "00", "6161"))
            assertThatThrownBy(() -> output(bad)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> output("82" + "00" + "00")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void aDatumOptionOfAnotherKindHoldsNoDatum() {
        TransactionOutput hash = output("a3" + "00" + ADDR + "0107" + "02" + "8200" + HASH32);
        assertThat(hash.getDatumHash()).isEqualTo(H32);
        assertThat(hash.getInlineDatum()).isNull();
        TransactionOutput inline = output("a4" + "00" + ADDR + "0107" + "02" + "8201" + "d818" + "421845" + "03"
                + "4101");
        assertThat(inline.getInlineDatum()).isEqualTo("1845");
        assertThat(inline.getScriptRef()).isEqualTo("01");
        TransactionOutput other = output("a3" + "00" + ADDR + "0107" + "02" + "8202" + "4101");
        assertThat(other.getDatumHash()).isNull();
        assertThat(other.getInlineDatum()).isNull();
        assertThatThrownBy(() -> output("a3" + "00" + ADDR + "0107" + "02" + "8100"))
                .isInstanceOf(RuntimeException.class);
        assertThat(output("83" + ADDR + "07" + HASH32).getDatumHash()).isEqualTo(H32);
    }

    @Test
    void aWithdrawalAmountIsAnyInteger() {
        Map<String, BigInteger> withdrawals = WithdrawalsSerializer.INSTANCE.deserializeDI(item("a3" + ACCOUNT + "20"
                + "41aa" + "c24101" + "41bb" + "6161"));
        assertThat(withdrawals).containsOnlyKeys(RA, "aa", "bb");
        assertThat(withdrawals.get(RA)).isEqualTo(BigInteger.ONE.negate());
        assertThat(withdrawals.get("aa")).isEqualTo(BigInteger.ONE);
        assertThat(withdrawals.get("bb")).isNull();
        assertThatThrownBy(() -> withdrawals.put("cc", BigInteger.ONE))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(WithdrawalsSerializer.INSTANCE.deserializeDI(item("a2" + "41aa" + "01" + "41aa" + "02")))
                .containsExactly(Map.entry("aa", BigInteger.TWO));
        assertThatThrownBy(() -> WithdrawalsSerializer.INSTANCE.deserializeDI(item("80")))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void anInputIsAnArrayOfTwo() {
        assertThat(TransactionInputSerializer.INSTANCE.deserializeDI(item(INPUT)))
                .isEqualTo(new TransactionInput(H32, 5));
        for (String bad : List.of("81" + HASH32, "83" + HASH32 + "0000", "a0", "82" + "00" + "00",
                "82" + HASH32 + "20"))
            assertThatThrownBy(() -> TransactionInputSerializer.INSTANCE.deserializeDI(item(bad)))
                    .isInstanceOf(RuntimeException.class);
    }

    @Test
    void differenceM3_anInputIndexPastAnIntIsRejected() {
        assertThat(TransactionInputSerializer.INSTANCE.deserializeDI(item("82" + HASH32 + "1a7fffffff")).getIndex())
                .isEqualTo(Integer.MAX_VALUE);
        for (String index : List.of("1a80000000", "1b0000000100000005"))
            for (int key : new int[]{0, 13, 18}) {
                String field = String.format(key < 24 ? "%02x" : "18%02x", key) + "81" + "82" + HASH32 + index;
                assertThatThrownBy(() -> body("a4" + MIN + field)).isInstanceOf(RuntimeException.class);
            }
    }

    @Test
    void aNetworkIdOutOfRangeIsLoggedAndLeftAtItsDefault() {
        assertThat(body("a4" + MIN + "0f" + "00").getNetowrkId()).isZero();
        assertThat(body("a4" + MIN + "0f" + "01").getNetowrkId()).isEqualTo(1);
        assertThat(body("a4" + MIN + "0f" + "02").getNetowrkId()).isZero();
        assertThat(body("a4" + MIN + "0f" + "1a7fffffff").getNetowrkId()).isZero();
    }

    @Test
    void differenceM3_aNetworkIdPastAnIntIsRejected() {
        for (String id : List.of("1a80000000", "1b0000000100000001"))
            assertThatThrownBy(() -> body("a4" + MIN + "0f" + id)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void aBlockMapsItsBodiesWithTheirCbor() {
        boolean before = YaciConfig.INSTANCE.isReturnTxBodyCbor();
        YaciConfig.INSTANCE.setReturnTxBodyCbor(true);
        try {
            Block block = BlockSerializer.INSTANCE.deserialize(CborLoader.getHexBytes("block/preprod292683.txt"));
            Set<String> hashes = new HashSet<>();
            for (TransactionBody body : block.getTransactionBodies()) {
                assertThat(body.getTxHash()).isEqualTo(
                        HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(bytes(body.getCbor()))));
                hashes.add(body.getTxHash());
            }
            assertThat(hashes).hasSize(3);
        } finally {
            YaciConfig.INSTANCE.setReturnTxBodyCbor(before);
        }
    }
}
