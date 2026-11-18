package com.bloxbean.cardano.yaci.core.model.serializers;

import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.spec.Era;
import com.bloxbean.cardano.yaci.core.model.AuxData;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.model.LeiosCertificate;
import com.bloxbean.cardano.yaci.core.model.PoolParams;
import com.bloxbean.cardano.yaci.core.model.ProtocolParamUpdate;
import com.bloxbean.cardano.yaci.core.model.Redeemer;
import com.bloxbean.cardano.yaci.core.model.RedeemerTag;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.Witnesses;
import com.bloxbean.cardano.yaci.core.model.governance.actions.GovAction;
import com.bloxbean.cardano.yaci.core.model.governance.actions.ParameterChangeAction;
import com.bloxbean.cardano.yaci.core.model.serializers.governance.GovActionSerializer;
import com.bloxbean.cardano.yaci.core.types.NonNegativeInterval;
import com.bloxbean.cardano.yaci.core.types.UnitInterval;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0003 D4b (M7c): how Yaci maps what CCL's views give for Dijkstra's fields: absent and empty, eras before
 * Dijkstra, the two era-less mappings, the constructors that are kept, JSON of keys 38 and 53, and the parameter update
 * writer.
 */
class DijkstraModelRulesTest {
    private static final String KEY = "11".repeat(28);
    private static final String SCRIPT = "22".repeat(28);
    private static final String ACCOUNT = "e1" + "33".repeat(28);
    private static final String MINIMAL_BODY = "0080" + "0180" + "0200";
    private static final String INPUT = "82" + "5820" + "44".repeat(32) + "00";

    private static TransactionBody body(String entries, int count, Era era) {
        return TransactionBodySerializer.INSTANCE.read(
                HexUtil.decodeHexString(String.format("%02x", 0xa0 + count) + entries), era);
    }

    /** Key 14 present but empty: guards are empty, and so are the required signers, as a Conway body's would be. */
    @Test
    void emptyGuardsGiveNoRequiredSigners() {
        TransactionBody body = body(MINIMAL_BODY + "0e80", 4, Era.Dijkstra);
        assertThat(body.getGuards()).isEmpty();
        assertThat(body.getRequiredSigners()).isEmpty();

        TransactionBody scriptOnly = body(MINIMAL_BODY + "0e81" + "8201581c" + SCRIPT, 4, Era.Dijkstra);
        assertThat(scriptOnly.getGuards()).hasSize(1);
        assertThat(scriptOnly.getRequiredSigners()).isEmpty();

        TransactionBody keyHashForm = body(MINIMAL_BODY + "0e81" + "581c" + KEY, 4, Era.Dijkstra);
        assertThat(keyHashForm.getGuards()).extracting(guard -> guard.getHash()).containsExactly(KEY);
        assertThat(keyHashForm.getRequiredSigners()).containsExactly(KEY);

        TransactionBody none = body(MINIMAL_BODY, 3, Era.Dijkstra);
        assertThat(none.getGuards()).isNull();
        assertThat(none.getRequiredSigners()).isNull();
    }

    /**
     * A sub-transaction body is mapped from CCL's view of it at sub level: keys 2, 13, 16, 17, 23 and 27, which the
     * ledger reads there as unknown keys (Dijkstra {@code TxBody.hs}), map to nothing.
     */
    @Test
    void aSubTransactionBodyMapsNoTopLevelOnlyKey() {
        String subTx = "83" + "a2" + "0080" + "0180" + "a0" + "f6";
        String subBody = "a8" + "0080" + "0180" + "0207" + "0d81" + INPUT + "10" + "82" + "4100" + "01" + "1105"
                + "1781" + subTx + "181b" + "a1" + "581d" + ACCOUNT + "01";
        TransactionBody top = body(MINIMAL_BODY + "1781" + "83" + subBody + "a0" + "f6", 4, Era.Dijkstra);
        assertThat(top.getSubTransactions()).hasSize(1);
        TransactionBody sub = top.getSubTransactions().get(0).getBody();
        assertThat(sub.getFee()).isNull();
        assertThat(sub.getCollateralInputs()).isNull();
        assertThat(sub.getCollateralReturn()).isNull();
        assertThat(sub.getTotalCollateral()).isNull();
        assertThat(sub.getSubTransactions()).isNull();
        assertThat(sub.getStartingAccountBalanceIntervals()).isNull();
        assertThat(sub.getTxHash()).isEqualTo(IndependentCbor.blake2b256(HexUtil.decodeHexString(subBody)));

        // The same bytes as a top-level body have them all.
        TransactionBody asTopLevel = TransactionBodySerializer.INSTANCE.read(HexUtil.decodeHexString(subBody),
                Era.Dijkstra);
        assertThat(asTopLevel.getFee()).isEqualTo(BigInteger.valueOf(7));
        assertThat(asTopLevel.getCollateralInputs()).hasSize(1);
        assertThat(asTopLevel.getCollateralReturn()).isNotNull();
        assertThat(asTopLevel.getTotalCollateral()).isEqualTo(BigInteger.valueOf(5));
        assertThat(asTopLevel.getSubTransactions()).hasSize(1);
        assertThat(asTopLevel.getStartingAccountBalanceIntervals()).hasSize(1);
    }

    /**
     * Sub-transactions nested 5,000 deep: only the first level is mapped, as a sub-transaction body has no key 23, so
     * the mapper neither recurses into them nor overflows the stack.
     */
    @Test
    void deeplyNestedSubTransactionsAreNotEntered() {
        StringBuilder body = new StringBuilder("a2" + "0080" + "0180");
        for (int i = 0; i < 5000; i++)
            body.insert(0, "a3" + "0080" + "0180" + "1781" + "83").append("a0" + "f6");
        TransactionBody top = TransactionBodySerializer.INSTANCE.read(HexUtil.decodeHexString(body.toString()),
                Era.Dijkstra);
        assertThat(top.getSubTransactions()).hasSize(1);
        assertThat(top.getSubTransactions().get(0).getBody().getSubTransactions()).isNull();
    }

    /** Keys 23 to 27 present but empty are invalid in Haskell, and read as absent: null. */
    @Test
    void emptySubTransactionsAndMapsAreNull() {
        TransactionBody body = body(MINIMAL_BODY + "1780" + "1818a0" + "1819a0" + "181aa0" + "181ba0", 8, Era.Dijkstra);
        assertThat(body.getSubTransactions()).isNull();
        assertThat(body.getRequiredTopLevelGuards()).isNull();
        assertThat(body.getDirectDeposits()).isNull();
        assertThat(body.getAccountBalanceIntervals()).isNull();
        assertThat(body.getStartingAccountBalanceIntervals()).isNull();
    }

    /**
     * Before Dijkstra, Dijkstra's keys are unknown, so the views give nothing and the models are as before: key 14 is
     * required signers, keys 23 to 27 are absent, a witness set's key 8 gives no scripts and aux data's key 5 none.
     */
    @Test
    void dijkstraKeysAreAbsentBeforeDijkstra() throws Exception {
        String dijkstraKeys = "0e81581c" + KEY + "1781" + "83a2" + "0080" + "0180" + "a0f6"
                + "1818a1" + "8200581c" + KEY + "f6" + "1819a1581d" + ACCOUNT + "01"
                + "181aa1581d" + ACCOUNT + "01" + "181ba1581d" + ACCOUNT + "01";
        TransactionBody conway = body(MINIMAL_BODY + dijkstraKeys, 9, Era.Conway);
        assertThat(conway.getRequiredSigners()).containsExactly(KEY);
        assertThat(conway.getGuards()).isNull();
        assertThat(conway.getSubTransactions()).isNull();
        assertThat(conway.getRequiredTopLevelGuards()).isNull();
        assertThat(conway.getDirectDeposits()).isNull();
        assertThat(conway.getAccountBalanceIntervals()).isNull();
        assertThat(conway.getStartingAccountBalanceIntervals()).isNull();

        TransactionBody dijkstra = body(MINIMAL_BODY + dijkstraKeys, 9, Era.Dijkstra);
        assertThat(dijkstra.getGuards()).hasSize(1);
        assertThat(dijkstra.getRequiredSigners()).containsExactly(KEY);
        assertThat(dijkstra.getSubTransactions()).hasSize(1);
        assertThat(dijkstra.getRequiredTopLevelGuards()).hasSize(1);
        assertThat(dijkstra.getDirectDeposits()).containsEntry(ACCOUNT, BigInteger.ONE);
        assertThat(dijkstra.getAccountBalanceIntervals().get(ACCOUNT).getExact()).isEqualTo(BigInteger.ONE);
        assertThat(dijkstra.getStartingAccountBalanceIntervals()).hasSize(1);

        byte[] witnessSet = HexUtil.decodeHexString("a10881" + "4401020304");
        Witnesses inConway = WitnessesSerializer.INSTANCE.read(CborSpan.of(witnessSet), Era.Conway, true);
        assertThat(inConway.getPlutusV4Scripts()).isEmpty();
        assertThat(new ObjectMapper().writeValueAsString(inConway)).doesNotContain("plutusV4Scripts");
        assertThat(WitnessesSerializer.INSTANCE.read(CborSpan.of(witnessSet), Era.Dijkstra, true)
                .getPlutusV4Scripts()).extracting(script -> script.getContent()).containsExactly("4401020304");

        byte[] auxData = HexUtil.decodeHexString("d90103a1" + "0581" + "4401020304");
        AuxData auxInConway = AuxDataSerializer.INSTANCE.read(CborSpan.of(auxData), Era.Conway, true);
        assertThat(auxInConway.getPlutusV4Scripts()).isNull();
        assertThat(new ObjectMapper().writeValueAsString(auxInConway)).doesNotContain("plutusV4Scripts");
        assertThat(AuxDataSerializer.INSTANCE.read(CborSpan.of(auxData), Era.Dijkstra, true).getPlutusV4Scripts())
                .extracting(script -> script.getContent()).containsExactly("01020304");
        assertThat(AuxDataSerializer.INSTANCE.read(CborSpan.of(HexUtil.decodeHexString("d90103a10580")),
                Era.Dijkstra, true).getPlutusV4Scripts()).isEmpty();
    }

    /**
     * D4b's second listed era-less mapping: redeemer tag 6 is {@code Guarding}, JSON {@code guarding}, in any era. M7a
     * left it null. Tag 6 is invalid before Dijkstra.
     */
    @Test
    void redeemerTagSixIsGuardingInAnyEra() throws Exception {
        byte[] witnessSet = HexUtil.decodeHexString("a105" + "a1" + "820600" + "82" + "00" + "820101");
        List<Witnesses> read = List.of(
                WitnessesSerializer.INSTANCE.read(CborSpan.of(witnessSet), Era.Conway, true),
                WitnessesSerializer.INSTANCE.read(CborSpan.of(witnessSet), Era.Dijkstra, true),
                WitnessesSerializer.INSTANCE.deserialize(witnessSet));
        for (Witnesses witnesses : read) {
            Redeemer redeemer = witnesses.getRedeemers().get(0);
            assertThat(redeemer.getTag()).isEqualTo(RedeemerTag.Guarding);
            assertThat(RedeemerTag.Guarding.value).isEqualTo(6);
            assertThat(new ObjectMapper().writeValueAsString(redeemer)).contains("\"tag\":\"guarding\"");
        }
        byte[] array = HexUtil.decodeHexString("a105" + "81" + "840600" + "00" + "820101");
        assertThat(WitnessesSerializer.INSTANCE.read(CborSpan.of(array), Era.Conway, true).getRedeemers().get(0)
                .getTag()).isEqualTo(RedeemerTag.Guarding);
    }

    /**
     * The kept constructors leave the new fields null, except the Plutus V4 scripts of a witness set, which are empty
     * as its other lists are. A block copied with toBuilder keeps them.
     */
    @Test
    void keptConstructorsLeaveTheNewFieldsUnset() {
        assertThat(new Witnesses(null, null, null, null, null, null, null, null).getPlutusV4Scripts()).isEmpty();
        assertThat(new Witnesses(null, null, null, null, null, null, null, null, null).getPlutusV4Scripts()).isEmpty();
        assertThat(new Witnesses().getPlutusV4Scripts()).isEmpty();
        assertThat(Witnesses.builder().build().getPlutusV4Scripts()).isEmpty();
        assertThat(new AuxData(null, null, null, null, null, null).getPlutusV4Scripts()).isNull();
        assertThat(new AuxData(null, null, null, null, null, null, null).getPlutusV4Scripts()).isNull();
        assertThat(new AuxData(null, null, null, null, null, null, null, null).getPlutusV4Scripts()).isNull();
        assertThat(new PoolParams(null, null, null, null, null, null, null, null, null, null).getBlsKey()).isNull();

        Block block = new Block(null, null, null, null, null, null, null);
        assertThat(block.getLeiosCertificate()).isNull();
        assertThat(block.getPerasCertificate()).isNull();
        HeaderBody header = new HeaderBody(1, 2, null, null, null, null, null, null, 3, null, null, null, null);
        assertThat(header.getLeiosCertInBody()).isNull();
        assertThat(header.getEbAnnouncement()).isNull();

        Block dijkstra = Block.builder().era(com.bloxbean.cardano.yaci.core.model.Era.Dijkstra)
                .leiosCertificate(new LeiosCertificate("c0", "aa")).perasCertificate("bb").build();
        Block copy = dijkstra.toBuilder().era(null).build();
        assertThat(copy.getEra()).isNull();
        assertThat(copy.getLeiosCertificate()).isEqualTo(dijkstra.getLeiosCertificate());
        assertThat(copy.getPerasCertificate()).isEqualTo("bb");
    }

    /**
     * Intervals compare by value: NonNegativeInterval's equals and hashCode take its numerator and denominator, so two
     * updates that differ only in an interval differ (before M7c every NonNegativeInterval was equal to every other).
     */
    @Test
    void intervalsCompareByTheirValues() {
        assertThat(nonNegative(1, 2)).isEqualTo(nonNegative(1, 2)).hasSameHashCodeAs(nonNegative(1, 2))
                .isNotEqualTo(nonNegative(10, 1)).isNotEqualTo(nonNegative(1, 3));
        assertThat(nonNegative(1, 2)).isNotEqualTo(unit(1, 2));
        assertThat(unit(1, 2)).isEqualTo(unit(1, 2)).isNotEqualTo(unit(1, 3));
        ProtocolParamUpdate half = ProtocolParamUpdate.builder().maxPledgeLeverage(Optional.of(nonNegative(1, 2)))
                .build();
        ProtocolParamUpdate ten = ProtocolParamUpdate.builder().maxPledgeLeverage(Optional.of(nonNegative(10, 1)))
                .build();
        assertThat(half).isNotEqualTo(ten);
        assertThat(ProtocolParamUpdate.builder().priceMem(nonNegative(577, 10000)).build())
                .isNotEqualTo(ProtocolParamUpdate.builder().priceMem(nonNegative(721, 10000)).build());
        assertThat(half).isEqualTo(ProtocolParamUpdate.builder().maxPledgeLeverage(Optional.of(nonNegative(1, 2)))
                .build());
    }

    /**
     * Keys 38 and 53 keep their three states, absent, nil and a value, through JSON with a bare mapper, as Yaci Store
     * reads Yaci's models, and with Jackson's JDK 8 module.
     */
    @Test
    void nilOrValueParametersRoundTripThroughJson() throws Exception {
        List<ProtocolParamUpdate> updates = List.of(
                ProtocolParamUpdate.builder().minFeeA(1).build(),
                ProtocolParamUpdate.builder().maxPledgeLeverage(Optional.empty())
                        .perasBootstrapRound(Optional.empty()).build(),
                ProtocolParamUpdate.builder().maxPledgeLeverage(Optional.of(nonNegative(3, 2)))
                        .perasBootstrapRound(Optional.of(4294967295L)).build(),
                ProtocolParamUpdate.builder().perasBootstrapRound(Optional.of(0L)).build());
        for (ObjectMapper mapper : List.of(new ObjectMapper(), new ObjectMapper().registerModule(new Jdk8Module()))) {
            for (ProtocolParamUpdate update : updates) {
                String json = mapper.writeValueAsString(update);
                ProtocolParamUpdate read = mapper.readValue(json, ProtocolParamUpdate.class);
                assertThat(read.getMaxPledgeLeverage()).as(json).isEqualTo(update.getMaxPledgeLeverage());
                assertThat(read.getPerasBootstrapRound()).as(json).isEqualTo(update.getPerasBootstrapRound());
                assertThat(read).as(json).isEqualTo(update);
                assertThat(mapper.writeValueAsString(read)).isEqualTo(json);
            }
        }
        String nil = new ObjectMapper().writeValueAsString(updates.get(1));
        assertThat(nil).contains("\"maxPledgeLeverage\":null", "\"perasBootstrapRound\":null");
        assertThat(new ObjectMapper().writeValueAsString(updates.get(0)))
                .doesNotContain("maxPledgeLeverage", "perasBootstrapRound");
        ProtocolParamUpdate populated = new ObjectMapper().readValue(
                new ObjectMapper().writeValueAsString(updates.get(2)), ProtocolParamUpdate.class);
        assertThat(populated.getMaxPledgeLeverage().get()).isInstanceOf(NonNegativeInterval.class);
        assertThat(populated.getMaxPledgeLeverage().get().getNumerator()).isEqualTo(BigInteger.valueOf(3));
        assertThat(populated.getMaxPledgeLeverage().get().getDenominator()).isEqualTo(BigInteger.valueOf(2));
        assertThat(populated.getPerasBootstrapRound()).contains(4294967295L);
    }

    /**
     * {@code serializePPUpdate} writes keys 34 to 56, and {@code deserialize(bytes, Dijkstra)} reads them back, at the
     * boundaries of their types: 2^31 and 2^32 - 1 for the Word32 keys, 2^15 and 2^16 - 1 for the Word16 keys, and 2^64
     * - 1 for the coins and execution units. Read without an era, as Conway, they are dropped.
     */
    @Test
    void dijkstraParameterChangesRoundTripThroughTheWriter() {
        for (long word32 : new long[]{1L << 31, 0xffffffffL}) {
            int word16 = word32 == 1L << 31 ? 1 << 15 : 0xffff;
            BigInteger large = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
            for (Optional<Long> bootstrap : List.of(Optional.of(word32), Optional.<Long>empty())) {
                ProtocolParamUpdate update = ProtocolParamUpdate.builder()
                        .minFeeA(44).maxBlockSize(90112)
                        .maxRefScriptSizePerBlock(word32).maxRefScriptSizePerTx(word32).refScriptCostStride(word32)
                        .refScriptCostMultiplier(nonNegative(12, 10))
                        .maxPledgeLeverage(bootstrap.isPresent() ? Optional.of(nonNegative(word32, 1))
                                : Optional.empty())
                        .minPoolMargin(unit(3, 200))
                        .leiosAnnouncementPeriodLength(word32).leiosVotePeriodLength(word32)
                        .leiosDiffusionPeriodLength(word32).leiosCommitteeSize(word16)
                        .leiosQuorumStakeThreshold(unit(3, 4))
                        .maxEndorserBlockReferencesSize(word32).maxEndorserBlockTxsSize(word32)
                        .maxEndorserBlockExMem(large).maxEndorserBlockExSteps(large)
                        .maxRefScriptSizePerEndorserBlock(word32).perasMinCandidateBlockAge(word32)
                        .perasHealingFactor(nonNegative(1, 2)).perasCertBoost(word16).perasTargetCommitteeSize(word16)
                        .perasBootstrapRound(bootstrap).perasQuorumThresholdSafetyMargin(unit(1, 20))
                        .refInputsCostPerMultiAssetPolicy(large).refInputsCostPerDatumByte(BigInteger.ZERO)
                        .build();
                ParameterChangeAction action = new ParameterChangeAction(null, update, "44".repeat(28));
                byte[] bytes = CborSerializationUtil.serialize(GovActionSerializer.INSTANCE.serializeDI(action));

                GovAction inDijkstra = GovActionSerializer.INSTANCE.deserialize(bytes, Era.Dijkstra);
                ProtocolParamUpdate read = ((ParameterChangeAction) inDijkstra).getProtocolParamUpdate();
                assertThat(read).isEqualTo(update);
                assertThat(read.getMaxRefScriptSizePerBlock()).isEqualTo(word32);
                assertThat(read.getPerasBootstrapRound()).isEqualTo(bootstrap);

                ProtocolParamUpdate conwayOnly = ProtocolParamUpdate.builder().minFeeA(44).maxBlockSize(90112)
                        .build();
                assertThat(((ParameterChangeAction) GovActionSerializer.INSTANCE.deserialize(bytes))
                        .getProtocolParamUpdate()).isEqualTo(conwayOnly);
                assertThat(((ParameterChangeAction) GovActionSerializer.INSTANCE.deserialize(bytes, Era.Conway))
                        .getProtocolParamUpdate()).isEqualTo(conwayOnly);
            }
        }
    }

    /** Each of keys 34 to 56 alone, so that no key is written under another key's number. */
    @Test
    void eachDijkstraParameterIsWrittenUnderItsKey() {
        java.util.Map<Integer, ProtocolParamUpdate> single = new LinkedHashMap<>();
        single.put(34, ProtocolParamUpdate.builder().maxRefScriptSizePerBlock(7L).build());
        single.put(35, ProtocolParamUpdate.builder().maxRefScriptSizePerTx(7L).build());
        single.put(36, ProtocolParamUpdate.builder().refScriptCostStride(7L).build());
        single.put(37, ProtocolParamUpdate.builder().refScriptCostMultiplier(nonNegative(7, 1)).build());
        single.put(38, ProtocolParamUpdate.builder().maxPledgeLeverage(Optional.of(nonNegative(7, 1))).build());
        single.put(39, ProtocolParamUpdate.builder().minPoolMargin(unit(7, 10)).build());
        single.put(40, ProtocolParamUpdate.builder().leiosAnnouncementPeriodLength(7L).build());
        single.put(41, ProtocolParamUpdate.builder().leiosVotePeriodLength(7L).build());
        single.put(42, ProtocolParamUpdate.builder().leiosDiffusionPeriodLength(7L).build());
        single.put(43, ProtocolParamUpdate.builder().leiosCommitteeSize(7).build());
        single.put(44, ProtocolParamUpdate.builder().leiosQuorumStakeThreshold(unit(7, 10)).build());
        single.put(45, ProtocolParamUpdate.builder().maxEndorserBlockReferencesSize(7L).build());
        single.put(46, ProtocolParamUpdate.builder().maxEndorserBlockTxsSize(7L).build());
        single.put(47, ProtocolParamUpdate.builder().maxEndorserBlockExMem(BigInteger.valueOf(7))
                .maxEndorserBlockExSteps(BigInteger.valueOf(8)).build());
        single.put(48, ProtocolParamUpdate.builder().maxRefScriptSizePerEndorserBlock(7L).build());
        single.put(49, ProtocolParamUpdate.builder().perasMinCandidateBlockAge(7L).build());
        single.put(50, ProtocolParamUpdate.builder().perasHealingFactor(nonNegative(7, 1)).build());
        single.put(51, ProtocolParamUpdate.builder().perasCertBoost(7).build());
        single.put(52, ProtocolParamUpdate.builder().perasTargetCommitteeSize(7).build());
        single.put(53, ProtocolParamUpdate.builder().perasBootstrapRound(Optional.of(7L)).build());
        single.put(54, ProtocolParamUpdate.builder().perasQuorumThresholdSafetyMargin(unit(7, 10)).build());
        single.put(55, ProtocolParamUpdate.builder().refInputsCostPerMultiAssetPolicy(BigInteger.valueOf(7)).build());
        single.put(56, ProtocolParamUpdate.builder().refInputsCostPerDatumByte(BigInteger.valueOf(7)).build());
        assertThat(single).hasSize(23);
        single.forEach((key, update) -> {
            CborSpan written = CborSpan.of(CborSerializationUtil.serialize(UpdateSerializer.serializePPUpdate(update)));
            assertThat(written.entries()).as("key " + key).hasSize(1);
            assertThat(written.entries().get(0).getKey().asLong()).as("key " + key).isEqualTo((long) key);
        });
        CborSpan nil = CborSpan.of(CborSerializationUtil.serialize(UpdateSerializer.serializePPUpdate(
                ProtocolParamUpdate.builder().maxPledgeLeverage(Optional.empty()).perasBootstrapRound(Optional.empty())
                        .build())));
        assertThat(nil.field(38).orElseThrow().isNull()).isTrue();
        assertThat(nil.field(53).orElseThrow().isNull()).isTrue();
        List<Long> conway = new ArrayList<>();
        CborSpan.of(CborSerializationUtil.serialize(UpdateSerializer.serializePPUpdate(
                ProtocolParamUpdate.builder().minFeeA(1).build()))).entries()
                .forEach(entry -> conway.add(entry.getKey().asLong()));
        assertThat(conway).containsExactly(0L);
    }

    private static NonNegativeInterval nonNegative(long numerator, long denominator) {
        return new NonNegativeInterval(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
    }

    private static UnitInterval unit(long numerator, long denominator) {
        return new UnitInterval(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
    }
}
