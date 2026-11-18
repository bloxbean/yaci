package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.model.*;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredType;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era;
import com.bloxbean.cardano.yaci.core.common.TxBodyType;
import com.bloxbean.cardano.yaci.core.types.NonNegativeInterval;
import com.bloxbean.cardano.yaci.core.types.UnitInterval;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dijkstra CDDL (cardano-blueprint 131cc95a) structures that the Haskell golden vectors do not contain.
 */
class DijkstraCddlTest {
    private static final String KEY_HASH = "f81ce66e0f52da5ca48193386e7511fde5b030a307b4c3681736c6f0";
    private static final String SCRIPT_HASH = "c28625280779716c698cc343cb9f247fe8f466fdfcd17274e61aeae9";
    private static final String REWARD_ACCOUNT = "e11f9d87172a78f61f248f28ae56b99c091b58236329802d8d48a909c6";

    @Test
    void requireGuardNativeScript() throws Exception {
        //script_require_guard = (6, credential)
        NativeScript keyGuard = WitnessesSerializer.INSTANCE.deserializeNativeScript(script(6, credential(0, KEY_HASH)));
        assertThat(keyGuard.getType()).isEqualTo(NativeScriptType.REQUIRE_GUARD);
        assertThat(json(keyGuard)).isEqualTo(json("{\"type\":\"requireGuard\",\"keyHash\":\"" + KEY_HASH + "\"}"));

        NativeScript scriptGuard = WitnessesSerializer.INSTANCE.deserializeNativeScript(script(6, credential(1, SCRIPT_HASH)));
        assertThat(json(scriptGuard)).isEqualTo(json("{\"type\":\"requireGuard\",\"scriptHash\":\"" + SCRIPT_HASH + "\"}"));

        //Nested in script_all
        Array children = new Array();
        children.add(script(0, new ByteString(bytes(KEY_HASH))));
        children.add(script(6, credential(1, SCRIPT_HASH)));
        NativeScript all = WitnessesSerializer.INSTANCE.deserializeNativeScript(script(1, children));
        assertThat(json(all)).isEqualTo(json("{\"type\":\"all\",\"scripts\":["
                + "{\"type\":\"sig\",\"keyHash\":\"" + KEY_HASH + "\"},"
                + "{\"type\":\"requireGuard\",\"scriptHash\":\"" + SCRIPT_HASH + "\"}]}"));
    }

    @Test
    void dijkstraBodyKeyHashGuardsAndStartingBalanceIntervals() {
        Map body = minimalBody();
        Array guards = new Array();
        guards.add(new ByteString(bytes(KEY_HASH))); //guards = nonempty_set<addr_keyhash>
        body.put(new UnsignedInteger(14), guards);
        Map starting = new Map();
        starting.put(new ByteString(bytes(REWARD_ACCOUNT)), new UnsignedInteger(7)); //coin: an exact balance
        body.put(new UnsignedInteger(27), starting);
        byte[] bytes = CborSerializationUtil.serialize(body);

        TransactionBody dijkstra = TransactionBodySerializer.INSTANCE.deserializeDI(body, bytes, true);
        assertThat(dijkstra.getGuards())
                .containsExactly(Credential.builder().type(StakeCredType.ADDR_KEYHASH).hash(KEY_HASH).build());
        assertThat(dijkstra.getRequiredSigners()).containsExactly(KEY_HASH);
        AccountBalanceInterval exact = dijkstra.getStartingAccountBalanceIntervals().get(REWARD_ACCOUNT);
        assertThat(exact.getExactBalance()).isEqualTo(7);
        assertThat(exact.getInclusiveLowerBound()).isEqualTo(7);
        assertThat(exact.getExclusiveUpperBound()).isEqualTo(8);

        //The same bytes read as before Dijkstra support (Conway path): nothing new is filled
        TransactionBody conway = TransactionBodySerializer.INSTANCE.deserializeDI(body, bytes);
        assertThat(conway.getRequiredSigners()).containsExactly(KEY_HASH);
        assertThat(conway.getGuards()).isNull();
        assertThat(conway.getStartingAccountBalanceIntervals()).isNull();
        assertThat(conway.getSubTransactions()).isNull();
    }

    @Test
    void conwayJsonHasNoDijkstraFields() throws Exception {
        Map body = minimalBody();
        TransactionBody conway = TransactionBodySerializer.INSTANCE.deserializeDI(body, CborSerializationUtil.serialize(body));

        String json = new ObjectMapper().writeValueAsString(conway);
        assertThat(json).doesNotContain("guards", "subTransactions", "requiredTopLevelGuards", "directDeposits",
                "accountBalanceIntervals", "startingAccountBalanceIntervals");
    }

    @Test
    void conwayPoolParamsAndProtocolParamUpdateJsonUnchanged() throws Exception {
        //Conway pool registration (10 items, no bls_key) and a Conway-only parameter update
        String poolJson = new ObjectMapper().writeValueAsString(PoolRegistrationSerializer.INSTANCE.deserializeDI(
                CborSerializationUtil.deserializeOne(bytes(CONWAY_POOL_REGISTRATION))).getPoolParams());
        assertThat(poolJson).doesNotContain("bls");

        Map conway = new Map();
        conway.put(new UnsignedInteger(0), new UnsignedInteger(44));
        String updateJson = new ObjectMapper().writeValueAsString(UpdateSerializer.INSTANCE.getProtocolParams(conway));
        assertThat(updateJson).doesNotContain("maxRefScriptSize", "refScriptCost", "maxPledgeLeverage", "minPoolMargin",
                "leios", "EndorserBlock", "peras", "refInputs");
    }

    @Test
    void subTransactionsTaggedWithNilAuxAndForbiddenKeysIgnored() {
        //sub_transaction_body has no keys 23 and 27: present anyway, they are not read
        Map subBody = minimalBody();
        subBody.remove(new UnsignedInteger(2));
        subBody.put(new UnsignedInteger(23), subTransactions(minimalBody()));
        Map starting = new Map();
        starting.put(new ByteString(bytes(REWARD_ACCOUNT)), new UnsignedInteger(5));
        subBody.put(new UnsignedInteger(27), starting);

        Array tagged = subTransactions(subBody);
        tagged.setTag(258); //nonempty_oset, with tag
        Map body = minimalBody();
        body.put(new UnsignedInteger(23), tagged);

        TransactionBody tx = TransactionBodySerializer.INSTANCE.deserializeDI(body, CborSerializationUtil.serialize(body), true);

        assertThat(tx.getSubTransactions()).hasSize(1);
        SubTransaction subTx = tx.getSubTransactions().get(0);
        assertThat(subTx.getAuxData()).isNull();
        assertThat(subTx.getWitnesses()).isNotNull();
        assertThat(subTx.getBody().getSubTransactions()).isNull();
        assertThat(subTx.getBody().getStartingAccountBalanceIntervals()).isNull();
        assertThat(subTx.getBody().getTxHash())
                .isEqualTo(com.bloxbean.cardano.yaci.core.util.TxUtil.calculateTxHash(CborSerializationUtil.serialize(subBody)));
    }

    @Test
    void unreadableDijkstraFieldIsLeftNullAndTheTransactionIsKept() {
        Map body = minimalBody();
        Array broken = new Array();
        broken.add(new Array().add(minimalBody()).add(new Map())); //sub_transaction with 2 items instead of 3
        body.put(new UnsignedInteger(23), broken);
        body.put(new UnsignedInteger(25), new UnsignedInteger(1)); //not a map
        body.put(new UnsignedInteger(26), new Map().put(new ByteString(bytes(REWARD_ACCOUNT)), new Array())); //no bounds

        TransactionBody tx = TransactionBodySerializer.INSTANCE.deserializeDI(body, CborSerializationUtil.serialize(body), true);

        assertThat(tx.getFee()).isEqualTo(0);
        assertThat(tx.getSubTransactions()).isNull();
        assertThat(tx.getDirectDeposits()).isNull();
        assertThat(tx.getAccountBalanceIntervals()).isNull();
    }

    @Test
    void unreadableBlsKeyIsLeftNull() {
        //An 11-item registration whose bls_key is a byte string instead of [bls_pubkey, proof]
        String conway = CONWAY_POOL_REGISTRATION;
        int afterVrf = 4 + 2 * (2 + 28) + 2 * (2 + 32);
        String dijkstra = "8b" + conway.substring(2, afterVrf) + "4100" + conway.substring(afterVrf);

        PoolParams params = PoolRegistrationSerializer.INSTANCE.deserializeDI(
                CborSerializationUtil.deserializeOne(bytes(dijkstra))).getPoolParams();

        assertThat(params.getBlsPublicKey()).isNull();
        assertThat(params.getPledge()).isEqualTo(100000000);
    }

    @Test
    void protocolParamUpdateDijkstraAndLeiosKeys() {
        ProtocolParamUpdate update = ProtocolParamUpdate.builder()
                .minFeeA(44)
                .maxRefScriptSizePerBlock(1048576L)
                .maxRefScriptSizePerTx(204800L)
                .refScriptCostStride(25600L)
                .refScriptCostMultiplier(new NonNegativeInterval(BigInteger.valueOf(6), BigInteger.valueOf(5)))
                .maxPledgeLeverage(new NonNegativeInterval(BigInteger.valueOf(100), BigInteger.ONE))
                .maxPledgeLeverageNoLimit(false)
                .minPoolMargin(new UnitInterval(BigInteger.ONE, BigInteger.valueOf(100)))
                .leiosAnnouncementPeriodMs(1000L)
                .leiosVotePeriodMs(4000L)
                .leiosDiffusionPeriodMs(7000L)
                .leiosCommitteeSize(600)
                .leiosQuorumStakeThreshold(new UnitInterval(BigInteger.valueOf(3), BigInteger.valueOf(4)))
                .maxEndorserBlockRefsSize(512000L)
                .maxEndorserBlockTxsSize(12500000L)
                .maxEndorserBlockExMem(BigInteger.valueOf(1000000000L))
                .maxEndorserBlockExSteps(BigInteger.valueOf(2000000000000L))
                .maxRefScriptSizePerEndorserBlock(10000000L)
                .build();

        ProtocolParamUpdate parsed = UpdateSerializer.INSTANCE.getProtocolParams(viaBytes(UpdateSerializer.serializePPUpdate(update)));

        assertThat(parsed.getMinFeeA()).isEqualTo(44);
        assertThat(parsed.getMaxRefScriptSizePerBlock()).isEqualTo(1048576L);
        assertThat(parsed.getMaxRefScriptSizePerTx()).isEqualTo(204800L);
        assertThat(parsed.getRefScriptCostStride()).isEqualTo(25600L);
        assertThat(ratio(parsed.getRefScriptCostMultiplier())).isEqualTo("6/5");
        assertThat(ratio(parsed.getMaxPledgeLeverage())).isEqualTo("100/1");
        assertThat(parsed.getMaxPledgeLeverageNoLimit()).isFalse();
        assertThat(ratio(parsed.getMinPoolMargin())).isEqualTo("1/100");
        assertThat(parsed.getLeiosAnnouncementPeriodMs()).isEqualTo(1000L);
        assertThat(parsed.getLeiosVotePeriodMs()).isEqualTo(4000L);
        assertThat(parsed.getLeiosDiffusionPeriodMs()).isEqualTo(7000L);
        assertThat(parsed.getLeiosCommitteeSize()).isEqualTo(600);
        assertThat(ratio(parsed.getLeiosQuorumStakeThreshold())).isEqualTo("3/4");
        assertThat(parsed.getMaxEndorserBlockRefsSize()).isEqualTo(512000L);
        assertThat(parsed.getMaxEndorserBlockTxsSize()).isEqualTo(12500000L);
        assertThat(parsed.getMaxEndorserBlockExMem()).isEqualTo(1000000000L);
        assertThat(parsed.getMaxEndorserBlockExSteps()).isEqualTo(2000000000000L);
        assertThat(parsed.getMaxRefScriptSizePerEndorserBlock()).isEqualTo(10000000L);

        //max_pledge_leverage = nonnegative_interval / nil: nil means no limit
        Map noLimit = new Map();
        noLimit.put(new UnsignedInteger(38), SimpleValue.NULL);
        ProtocolParamUpdate unlimited = UpdateSerializer.INSTANCE.getProtocolParams(noLimit);
        assertThat(unlimited.getMaxPledgeLeverageNoLimit()).isTrue();
        assertThat(unlimited.getMaxPledgeLeverage()).isNull();
        assertThat(CborSerializationUtil.serialize(UpdateSerializer.serializePPUpdate(unlimited)))
                .isEqualTo(CborSerializationUtil.serialize(noLimit));

        //An update without the new keys leaves them null
        Map conway = new Map();
        conway.put(new UnsignedInteger(0), new UnsignedInteger(44));
        ProtocolParamUpdate old = UpdateSerializer.INSTANCE.getProtocolParams(conway);
        assertThat(old.getMaxPledgeLeverageNoLimit()).isNull();
        assertThat(old.getLeiosCommitteeSize()).isNull();
        assertThat(old.getPerasBootstrapRoundNone()).isNull();
        assertThat(old.getRefInputsCostPerDatumByte()).isNull();
    }

    @Test
    void protocolParamUpdatePerasAndReferenceInputFeeKeys() {
        //Keys 49-56, added by Musashi rc-2026w42 (cardano-ledger 9df9f98c)
        ProtocolParamUpdate update = ProtocolParamUpdate.builder()
                .perasMinCandidateBlockAge(30L)
                .perasHealingFactor(new NonNegativeInterval(BigInteger.valueOf(3), BigInteger.valueOf(2)))
                .perasCertBoost(15)
                .perasTargetCommitteeSize(500)
                .perasBootstrapRound(4000000000L)
                .perasBootstrapRoundNone(false)
                .perasQuorumThresholdSafetyMargin(new UnitInterval(BigInteger.ONE, BigInteger.valueOf(10)))
                .refInputsCostPerMultiAssetPolicy(BigInteger.valueOf(1000))
                .refInputsCostPerDatumByte(BigInteger.valueOf(44))
                .build();

        Map map = (Map) UpdateSerializer.serializePPUpdate(update);
        assertThat(map.getKeys()).extracting(key -> ((UnsignedInteger) key).getValue().intValue())
                .containsExactly(49, 50, 51, 52, 53, 54, 55, 56);
        ProtocolParamUpdate parsed = UpdateSerializer.INSTANCE.getProtocolParams(viaBytes(map));

        assertThat(parsed.getPerasMinCandidateBlockAge()).isEqualTo(30L);
        assertThat(ratio(parsed.getPerasHealingFactor())).isEqualTo("3/2");
        assertThat(parsed.getPerasCertBoost()).isEqualTo(15);
        assertThat(parsed.getPerasTargetCommitteeSize()).isEqualTo(500);
        assertThat(parsed.getPerasBootstrapRound()).isEqualTo(4000000000L);
        assertThat(parsed.getPerasBootstrapRoundNone()).isFalse();
        assertThat(ratio(parsed.getPerasQuorumThresholdSafetyMargin())).isEqualTo("1/10");
        assertThat(parsed.getRefInputsCostPerMultiAssetPolicy()).isEqualTo(1000);
        assertThat(parsed.getRefInputsCostPerDatumByte()).isEqualTo(44);

        //peras_bootstrap_round = uint / nil: nil means no bootstrap round
        Map none = new Map();
        none.put(new UnsignedInteger(53), SimpleValue.NULL);
        ProtocolParamUpdate noBootstrap = UpdateSerializer.INSTANCE.getProtocolParams(none);
        assertThat(noBootstrap.getPerasBootstrapRoundNone()).isTrue();
        assertThat(noBootstrap.getPerasBootstrapRound()).isNull();
        assertThat(CborSerializationUtil.serialize(UpdateSerializer.serializePPUpdate(noBootstrap)))
                .isEqualTo(CborSerializationUtil.serialize(none));
    }

    @Test
    void dijkstraEraIndex() {
        //Hard-fork era index used by local state queries and transaction submission
        assertThat(Era.Dijkstra.getValue()).isEqualTo(7);
        assertThat(Era.fromInt(7)).isEqualTo(Era.Dijkstra);
        assertThat(TxBodyType.DIJKSTRA.getValue()).isEqualTo(7);
        assertThat(TxBodyType.DIJKSTRA.getEra()).isEqualTo(Era.Dijkstra);
    }

    private static String ratio(UnitInterval interval) {
        return interval.getNumerator() + "/" + interval.getDenominator();
    }

    //As on the wire: decoding turns #6.30 rationals into RationalNumber
    private static Map viaBytes(co.nstant.in.cbor.model.DataItem map) {
        return (Map) CborSerializationUtil.deserializeOne(CborSerializationUtil.serialize(map));
    }

    private static final String CONWAY_POOL_REGISTRATION = "8a03581ce319e1a85cf976abef526d34d223392b5a3f4a799d090492828999915820ca6306b471f5937ac69bda9b04915ff71a450644f36ea6163403dc2666659b8b1a05f5e1001a1443fd00d81e82011864581de1ab737aaf2b421c22a15de788d61d375b135da0cbf1850fbd285d5a7481581cab737aaf2b421c22a15de788d61d375b135da0cbf1850fbd285d5a74828400191770442f5b1524f68400191770442f4a2a5ef6827668747470733a2f2f6269742e6c792f334166777652515820e413cf3890f9a323c4b0e2cbdf06c6f66a2b0ff1f84b1a2346178e9b54dfff0c";

    /** [[sub_transaction_body, {}, nil]] */
    private static Array subTransactions(Map subBody) {
        Array subTransaction = new Array();
        subTransaction.add(subBody);
        subTransaction.add(new Map());
        subTransaction.add(SimpleValue.NULL);
        return new Array().add(subTransaction);
    }

    private static Map minimalBody() {
        Map body = new Map();
        body.put(new UnsignedInteger(0), new Array());
        body.put(new UnsignedInteger(1), new Array());
        body.put(new UnsignedInteger(2), new UnsignedInteger(0));
        return body;
    }

    private static Array script(int type, co.nstant.in.cbor.model.DataItem value) {
        Array script = new Array();
        script.add(new UnsignedInteger(type));
        script.add(value);
        return script;
    }

    private static Array credential(int type, String hash) {
        Array credential = new Array();
        credential.add(new UnsignedInteger(type));
        credential.add(new ByteString(bytes(hash)));
        return credential;
    }

    private static byte[] bytes(String hex) {
        return com.bloxbean.cardano.yaci.core.util.HexUtil.decodeHexString(hex);
    }

    private static com.fasterxml.jackson.databind.JsonNode json(NativeScript script) throws Exception {
        return json(script.getContent());
    }

    private static com.fasterxml.jackson.databind.JsonNode json(String json) throws Exception {
        return new ObjectMapper().readTree(json);
    }
}
