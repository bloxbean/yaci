package com.bloxbean.cardano.yaci.core.model.serializers;

import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.yaci.core.model.AccountBalanceInterval;
import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.AuxData;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Credential;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.model.PlutusScript;
import com.bloxbean.cardano.yaci.core.model.PlutusScriptType;
import com.bloxbean.cardano.yaci.core.model.PoolParams;
import com.bloxbean.cardano.yaci.core.model.Redeemer;
import com.bloxbean.cardano.yaci.core.model.RedeemerTag;
import com.bloxbean.cardano.yaci.core.model.SubTransaction;
import com.bloxbean.cardano.yaci.core.model.TopLevelGuard;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionInput;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yaci.core.model.Witnesses;
import com.bloxbean.cardano.yaci.core.model.certs.PoolRegistration;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredType;
import com.bloxbean.cardano.yaci.core.util.CborLoader;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * ADR 0003 D4b, I16 (M7c): Dijkstra's fields in Yaci's models, read from the Dijkstra vectors in {@code block/dijkstra}
 * (sources in {@code SOURCES.txt}). Each expected value is taken from the vector's source, not from this code: the
 * Haskell definition of the ledger's golden transaction, the explorer's JSON of the KleioScan transactions, and the
 * fixture notes of pallas and gouroboros. Where a source records only a shape, a value is read from the input bytes
 * with {@link IndependentCbor}, and an id or hash taken over bytes located with CCL's {@link CborSpan} and hashed with
 * BouncyCastle's Blake2b. Transactions are read inside a Dijkstra
 * block, as block fetch delivers them: each replaces musashi-dijkstra2's transaction, whose header then no longer
 * matches its body, which Yaci does not check.
 */
class DijkstraModelsTest {
    private static final String KEY_GUARD_211 = mkKeyHash(211);
    private static final String KEY_GUARD_212 = mkKeyHash(212);
    private static final String SCRIPT_GUARD_213 = mkKeyHash(213);

    /**
     * cardano-ledger 95b36905, {@code exampleDijkstraTx} ({@code eras/dijkstra/impl/testlib/.../Examples.hs}). The
     * hashes are {@code mkKeyHash n} and {@code mkScriptHash n}: Blake2b-224 of {@code n} encoded as CBOR
     * ({@code mkDummyHash}, {@code libs/cardano-ledger-binary/testlib/.../Random.hs}). A {@code Mainnet} account
     * address of a key hash has header {@code e1}, of a script hash {@code f1}.
     */
    @Test
    void theLedgerGoldenTransactionHasItsSourcesFields() throws Exception {
        CborSpan source = CborSpan.of(CborLoader.getHexBytes("block/dijkstra/ledger-golden-tx.txt"));
        Tx tx = transaction("ledger-golden-tx");

        // guardsTxBodyL .~ OSet.fromList [KeyHashObj (mkKeyHash 211), KeyHashObj (mkKeyHash 212), ScriptHashObj 213]
        List<Credential> guards = List.of(key(KEY_GUARD_211), key(KEY_GUARD_212), script(SCRIPT_GUARD_213));
        // directDepositsTxBodyL .~ (AccountAddress Mainnet (KeyHashObj (mkKeyHash 300)), Coin 1_000_000)
        Map<String, BigInteger> deposits = Map.of("e1" + mkKeyHash(300), BigInteger.valueOf(1_000_000));
        // accountBalanceIntervalsTxBodyL: 400 Inclusive 500; 401 Exclusive 10_000; script 402 [100, 5000)
        Map<String, AccountBalanceInterval> intervals = Map.of(
                "e1" + mkKeyHash(400), new AccountBalanceInterval(null, BigInteger.valueOf(500), null),
                "e1" + mkKeyHash(401), new AccountBalanceInterval(null, null, BigInteger.valueOf(10_000)),
                "f1" + mkKeyHash(402), new AccountBalanceInterval(null, BigInteger.valueOf(100),
                        BigInteger.valueOf(5000)));

        // addDijkstraBasedTxFeatures applies to the top-level transaction and to its sub-transaction alike.
        CborSpan subTx = source.get(0).field(23).orElseThrow().untagIf(258).get(0);
        assertThat(tx.body().getSubTransactions()).hasSize(1);
        SubTransaction sub = tx.body().getSubTransactions().get(0);
        assertThat(sub.getBody().getTxHash()).isEqualTo(blake2b256(subTx.get(0).bytes()));
        List<TransactionBody> bodies = List.of(tx.body(), sub.getBody());
        List<Witnesses> witnesses = List.of(tx.witnesses(), sub.getWitnesses());
        List<AuxData> auxData = List.of(tx.auxData(), sub.getAuxData());
        // exampleBlsKey is made by BLS key generation, so its bytes are read from the source's registration with the
        // BLS key slot, 11 items where one without has 10: [3, operator, vrf, [public key, proof], ...]
        Object golden = IndependentCbor.read(CborLoader.getHexBytes("block/dijkstra/ledger-golden-tx.txt"));
        List<String> blsKey = IndependentCbor.items(IndependentCbor.field(IndependentCbor.get(golden, 0), 4)).stream()
                .filter(cert -> IndependentCbor.items(cert).size() == 11).map(cert -> IndependentCbor.get(cert, 3))
                .map(key -> List.of(IndependentCbor.hex(IndependentCbor.get(key, 0)),
                        IndependentCbor.hex(IndependentCbor.get(key, 1))))
                .findFirst().orElseThrow();
        assertThat(blsKey.get(0)).hasSize(2 * 96);
        assertThat(blsKey.get(1)).hasSize(2 * 48);
        for (int i = 0; i < 2; i++) {
            TransactionBody body = bodies.get(i);
            assertThat(body.getGuards()).containsExactlyElementsOf(guards);
            assertThat(body.getRequiredSigners()).containsExactlyInAnyOrder(KEY_GUARD_211, KEY_GUARD_212);
            assertThat(body.getDirectDeposits()).containsExactlyInAnyOrderEntriesOf(deposits);
            assertThat(body.getAccountBalanceIntervals()).containsExactlyInAnyOrderEntriesOf(intervals);

            // scriptTxWitsL <>~ [alwaysSucceeds @PlutusV4 3]; rdmrsTxWitsL: GuardingPurpose (AsIx 3), ExUnits 5000 5000
            assertThat(witnesses.get(i).getPlutusV4Scripts()).hasSize(1)
                    .allMatch(script -> script.getType() == PlutusScriptType.PlutusScriptV4);
            Redeemer guarding = witnesses.get(i).getRedeemers().stream()
                    .filter(redeemer -> redeemer.getTag() == RedeemerTag.Guarding).findFirst().orElseThrow();
            assertThat(guarding.getIndex()).isEqualTo(3);
            assertThat(guarding.getExUnits().getMem()).isEqualTo(BigInteger.valueOf(5000));
            assertThat(guarding.getExUnits().getSteps()).isEqualTo(BigInteger.valueOf(5000));
            // Data (Constr 1 [List [I 1], Map [(I 2, B "2")]]), as Plutus encodes it
            assertThat(guarding.getData().getCbor()).isEqualTo("d87a" + "9f" + "9f01ff" + "a1024132" + "ff");

            // plutusScriptsTxAuxDataL inserts plutusBinary (alwaysSucceedsPlutus @PlutusV4 3): the same script, which
            // the aux data gives as its bytes and the witness set as their CBOR
            assertThat(auxData.get(i).getPlutusV4Scripts()).hasSize(1);
            PlutusScript inAuxData = auxData.get(i).getPlutusV4Scripts().get(0);
            assertThat(inAuxData.getType()).isEqualTo(PlutusScriptType.PlutusScriptV4);
            assertThat(witnesses.get(i).getPlutusV4Scripts().get(0).getContent())
                    .isEqualTo(byteString(inAuxData.getContent()));

            // certsTxBodyL <>~ [RegPoolTxCert exampleStakePoolParams { sppBlsKey = SJust exampleBlsKey }], after the
            // Conway example's registration of exampleStakePoolParams
            List<PoolParams> pools = body.getCertificates().stream().filter(PoolRegistration.class::isInstance)
                    .map(cert -> ((PoolRegistration) cert).getPoolParams()).collect(Collectors.toList());
            assertThat(pools).hasSize(2);
            assertThat(pools.get(0).getBlsKey()).isNull();
            assertThat(pools.get(1).getBlsKey().getPublicKey()).isEqualTo(blsKey.get(0));
            assertThat(pools.get(1).getBlsKey().getProofOfPossession()).isEqualTo(blsKey.get(1));
            assertThat(pools.get(1)).usingRecursiveComparison().ignoringFields("blsKey").isEqualTo(pools.get(0));
        }

        // The top-level transaction has no key 24 or 27 (only the sub-transaction gets required top-level guards)
        assertThat(tx.body().getRequiredTopLevelGuards()).isNull();
        assertThat(tx.body().getStartingAccountBalanceIntervals()).isNull();
        assertThat(tx.body().getFee()).isNotNull();

        // addDijkstraBasedSubTxFeatures: (KeyHashObj (mkKeyHash 212), SNothing),
        // (ScriptHashObj (mkScriptHash 213), SJust exampleDatum)
        assertThat(sub.getBody().getRequiredTopLevelGuards()).hasSize(2);
        TopLevelGuard keyGuard = topLevelGuard(sub.getBody(), StakeCredType.ADDR_KEYHASH);
        TopLevelGuard scriptGuard = topLevelGuard(sub.getBody(), StakeCredType.SCRIPTHASH);
        assertThat(keyGuard.getCredential()).isEqualTo(key(KEY_GUARD_212));
        assertThat(keyGuard.getDatum()).isNull();
        assertThat(scriptGuard.getCredential()).isEqualTo(script(SCRIPT_GUARD_213));
        CborSpan datum = subTx.get(0).field(24).orElseThrow().entries().stream().map(Map.Entry::getValue)
                .filter(value -> !value.isNull()).findFirst().orElseThrow();
        assertThat(scriptGuard.getDatum().getCbor()).isEqualTo(HexUtil.encodeHexString(datum.bytes()));
        assertThat(scriptGuard.getDatum().getHash()).isEqualTo(blake2b256(datum.bytes()));
        assertSubTransactionBody(sub.getBody());
    }

    /**
     * KleioScan's JSON for dac250a5 lists one sub-transaction, 5d76e2e9, whose own JSON gives its parent, inputs,
     * outputs, mint and Plutus V4 script ({@code script_cbor} 450100002499); 4b97d4ad lists e2078c8e.
     */
    @Test
    void musashiSubTransactionsHaveTheExplorersIdsInputsAndOutputs() throws Exception {
        Tx parent = transaction("kleioscan-musashi-dac250a5");
        assertThat(parent.body().getTxHash()).isEqualTo(
                "dac250a5629c8b6f5d76349588ae5b7fd1762ca6523057748d5aa14762682b94");
        assertThat(parent.body().getSubTransactions()).hasSize(1);
        SubTransaction sub = parent.body().getSubTransactions().get(0);
        TransactionBody body = sub.getBody();
        assertThat(body.getTxHash()).isEqualTo("5d76e2e95326df264b9772a1b097957f8eb6a18e7333ab446bac8bb7bfdb92cb");
        assertThat(body.getInputs()).containsExactlyInAnyOrder(
                new TransactionInput("9f078089c26b8be6f2cc16cbed4c0f141821bffe062493731ed39ed85458ecf3", 0),
                new TransactionInput("808bb0c75c429fb5fa706c8ffdc38c53a4bd2afab7f6cc38fa54c1ea122848f9", 0));
        assertThat(body.getOutputs()).hasSize(1);
        TransactionOutput output = body.getOutputs().get(0);
        assertThat(output.getAddress()).isEqualTo("addr_test1qqz687wgyptmg87zdh3a9m86056det7tutlvjh6kjk79zruyfg6xfxf6"
                + "kc97cyg4zx36uh9wv3x3g3e98plume75euvq02f2zu");
        String policy = "1fd0190f1816f66a28208ce5836f64f54d99bdc2a088fee8e14547ed";
        assertThat(output.getAmounts()).extracting(Amount::getUnit, Amount::getQuantity).containsExactlyInAnyOrder(
                tuple("lovelace", BigInteger.valueOf(74_000_000)),
                tuple(policy + ".63617264616e6f2d632d76342d737562", BigInteger.ONE),
                tuple(policy + ".6c6163652d76342d737562", BigInteger.ONE));
        assertThat(body.getMint()).extracting(Amount::getUnit, Amount::getQuantity)
                .containsExactly(tuple(policy + ".6c6163652d76342d737562", BigInteger.ONE));
        assertThat(sub.getWitnesses().getPlutusV4Scripts()).extracting(PlutusScript::getContent)
                .containsExactly(byteString("450100002499"));
        assertSubTransactionBody(body);

        // The same sub-transaction, submitted on its own
        Tx alone = transaction("kleioscan-musashi-5d76e2e9");
        assertThat(alone.body().getTxHash()).isEqualTo(body.getTxHash());
        assertThat(alone.body().getInputs()).isEqualTo(body.getInputs());
        assertThat(alone.witnesses().getPlutusV4Scripts()).extracting(PlutusScript::getContent)
                .containsExactly(byteString("450100002499"));

        assertThat(transaction("kleioscan-musashi-4b97d4ad").body().getSubTransactions())
                .extracting(subTx -> subTx.getBody().getTxHash())
                .containsExactly("e2078c8e951d8bd0f82580a4435510361d96ddc037ee22dc4819c278e9aa8845");
    }

    /**
     * pallas {@code dijkstra-subtx.tx}: body keys 0, 1, 2, 13, 18 and 23, one sub-transaction whose body has keys 0, 1,
     * 11, 14 and 18, its guards key hashes. Its inputs and its outputs' count are read from the bytes.
     */
    @Test
    void theMusashiSubTransactionBlockHasKeyGuardsInItsSubTransaction() throws Exception {
        CborSpan source = CborSpan.of(CborLoader.getHexBytes("block/dijkstra/musashi-subtx.txt"));
        CborSpan subBody = source.get(0).field(23).orElseThrow().untagIf(258).get(0).get(0);
        Tx tx = transaction("musashi-subtx");
        assertThat(tx.body().getTxHash()).isEqualTo("74e2116ca6e0c809f156aa062a9d4ee8b156322618846f1bdea5d9ad7a206a12");
        assertThat(tx.body().getGuards()).isNull();
        assertThat(tx.body().getRequiredSigners()).isNull();
        assertThat(tx.body().getCollateralInputs()).isNotEmpty();
        assertThat(tx.body().getReferenceInputs()).isNotEmpty();
        assertThat(tx.body().getSubTransactions()).hasSize(1);

        TransactionBody sub = tx.body().getSubTransactions().get(0).getBody();
        assertThat(sub.getTxHash()).isEqualTo(blake2b256(subBody.bytes()));
        assertThat(sub.getInputs()).containsExactlyInAnyOrderElementsOf(inputs(subBody.field(0).orElseThrow()));
        assertThat(sub.getOutputs()).hasSize(subBody.field(1).orElseThrow().size());
        assertThat(sub.getScriptDataHash()).isNotNull();
        assertThat(sub.getReferenceInputs()).isNotEmpty();
        assertThat(sub.getGuards()).isNotEmpty().allMatch(guard -> guard.getType() == StakeCredType.ADDR_KEYHASH);
        assertThat(sub.getRequiredSigners()).containsExactlyInAnyOrderElementsOf(
                sub.getGuards().stream().map(Credential::getHash).collect(Collectors.toList()));
        assertSubTransactionBody(sub);
    }

    /**
     * pallas {@code dijkstra-indef-bytes1.tx}: real reward-account-keyed account balance intervals and starting
     * intervals, direct deposits and required top-level guards; a guard key hash and two account keys in the body, and
     * the same in the sub-transaction, written as indefinite-length byte strings. The source records no values, so
     * each is read from the bytes with {@link IndependentCbor}.
     */
    @Test
    void theIndefiniteBytesTransactionHasItsIntervalsDepositsAndGuards() throws Exception {
        Object source = IndependentCbor.read(CborLoader.getHexBytes("block/dijkstra/musashi-indef-bytes1.txt"));
        Object sourceBody = IndependentCbor.get(source, 0);
        Object sourceSub = IndependentCbor.get(IndependentCbor.get(IndependentCbor.field(sourceBody, 23), 0), 0);
        Tx tx = transaction("musashi-indef-bytes1");
        TransactionBody body = tx.body();
        assertThat(body.getTxHash()).isEqualTo("943a88c97f86522b2cda39d1d6bd076ea51a696e5ad41c41e4c6d38ce8f7dd50");
        List<String> guards = IndependentCbor.items(IndependentCbor.field(sourceBody, 14)).stream()
                .map(DijkstraModelsTest::guardKeyHash).collect(Collectors.toList());
        assertThat(guards).hasSize(1);
        assertThat(body.getGuards()).containsExactly(key(guards.get(0)));
        assertThat(body.getRequiredSigners()).containsExactly(guards.get(0));
        assertThat(body.getAccountBalanceIntervals()).isEqualTo(intervals(IndependentCbor.field(sourceBody, 26)));
        assertThat(body.getStartingAccountBalanceIntervals())
                .isEqualTo(intervals(IndependentCbor.field(sourceBody, 27)));
        assertThat(body.getStartingAccountBalanceIntervals().values())
                .allMatch(interval -> interval.getExact() != null);

        assertThat(body.getSubTransactions()).hasSize(1);
        TransactionBody sub = body.getSubTransactions().get(0).getBody();
        List<Map.Entry<Object, Object>> topLevelGuards = IndependentCbor.entries(IndependentCbor.field(sourceSub, 24));
        assertThat(sub.getRequiredTopLevelGuards()).extracting(TopLevelGuard::getCredential)
                .containsExactly(key(guardKeyHash(topLevelGuards.get(0).getKey())));
        assertThat(topLevelGuards.get(0).getValue()).isSameAs(IndependentCbor.NULL);
        assertThat(sub.getRequiredTopLevelGuards().get(0).getDatum()).isNull();
        Map<String, BigInteger> deposits = new java.util.LinkedHashMap<>();
        for (Map.Entry<Object, Object> entry : IndependentCbor.entries(IndependentCbor.field(sourceSub, 25)))
            deposits.put(IndependentCbor.hex(entry.getKey()), (BigInteger) entry.getValue());
        assertThat(sub.getDirectDeposits()).isEqualTo(deposits);
        assertThat(sub.getAccountBalanceIntervals()).isEqualTo(intervals(IndependentCbor.field(sourceSub, 26)));
        assertSubTransactionBody(sub);
    }

    /** A guard of the key-hash form, or a credential [0, key hash]. */
    private static String guardKeyHash(Object guard) {
        Object item = IndependentCbor.untagged(guard);
        if (item instanceof byte[])
            return IndependentCbor.hex(item);
        assertThat(IndependentCbor.get(item, 0)).isEqualTo(BigInteger.ZERO);
        return IndependentCbor.hex(IndependentCbor.get(item, 1));
    }

    /** reward_account => coin / [lower / nil, upper / nil], every entry. */
    private static Map<String, AccountBalanceInterval> intervals(Object map) {
        Map<String, AccountBalanceInterval> intervals = new java.util.LinkedHashMap<>();
        for (Map.Entry<Object, Object> entry : IndependentCbor.entries(map)) {
            Object value = entry.getValue();
            intervals.put(IndependentCbor.hex(entry.getKey()), value instanceof BigInteger exact
                    ? new AccountBalanceInterval(exact, null, null)
                    : new AccountBalanceInterval(null, bound(IndependentCbor.get(value, 0)),
                    bound(IndependentCbor.get(value, 1))));
        }
        return intervals;
    }

    private static BigInteger bound(Object bound) {
        return bound == IndependentCbor.NULL ? null : (BigInteger) bound;
    }

    /**
     * pallas {@code dijkstra-v4-witness.tx} and {@code dijkstra-v4-sub-witness.tx}: the script at witness key 8 is
     * 450101002499, at the top level, or only in the second of two sub-transactions.
     */
    @Test
    void plutusV4ScriptsAreWhereTheirSourceSays() throws Exception {
        String script = byteString("450101002499");
        Tx topLevel = transaction("rc-2026w42-v4-witness");
        assertThat(topLevel.body().getTxHash())
                .isEqualTo("931a78483ed17437655db1ad49b1920f4cef45b3c48c165acd42e6759933d358");
        assertThat(topLevel.witnesses().getPlutusV4Scripts())
                .extracting(PlutusScript::getType, PlutusScript::getContent)
                .containsExactly(tuple(PlutusScriptType.PlutusScriptV4, script));

        CborSpan source = CborSpan.of(CborLoader.getHexBytes("block/dijkstra/rc-2026w42-v4-sub-witness.txt"));
        List<CborSpan> subs = source.get(0).field(23).orElseThrow().untagIf(258).items();
        Tx inSub = transaction("rc-2026w42-v4-sub-witness");
        assertThat(inSub.body().getTxHash())
                .isEqualTo("a9e1e0c39a9d4536ad2cd75c6eac0aead02300adfb12e013147c21cdb8156ada");
        assertThat(inSub.witnesses().getPlutusV4Scripts()).isEmpty();
        assertThat(inSub.body().getSubTransactions()).extracting(sub -> sub.getBody().getTxHash())
                .containsExactly(blake2b256(subs.get(0).get(0).bytes()), blake2b256(subs.get(1).get(0).bytes()));
        assertThat(inSub.body().getSubTransactions().get(0).getWitnesses().getPlutusV4Scripts()).isEmpty();
        assertThat(inSub.body().getSubTransactions().get(1).getWitnesses().getPlutusV4Scripts())
                .extracting(PlutusScript::getContent).containsExactly(script);
    }

    /**
     * pallas: dijkstra8 is the first Leios certificate, with an announcement; dijkstra9 a certificate without an
     * announcement; dijkstra15 announces an endorser block of 71103 bytes; their certificates have 11-byte bit fields.
     * gouroboros: the rc-2026w41 certifying block is certified, with signers {@code c0}. Peras certificates are nil in
     * every real block, and a 10-item DijkstraNet header has no Leios fields.
     */
    @Test
    void leiosFieldsAreThoseTheSourcesRecord() {
        Block first = block("musashi-dijkstra1");
        assertThat(first.getLeiosCertificate()).isNull();
        assertThat(first.getHeader().getHeaderBody().getLeiosCertInBody()).isFalse();

        for (String name : List.of("musashi-dijkstra8", "musashi-dijkstra9", "musashi-dijkstra15")) {
            Block block = block(name);
            HeaderBody header = block.getHeader().getHeaderBody();
            assertThat(header.getLeiosCertInBody()).as(name).isTrue();
            assertThat(block.getLeiosCertificate().getSigners()).as(name).hasSize(2 * 11);
            assertLeiosSignature(block, name);
        }
        assertThat(block("musashi-dijkstra8").getHeader().getHeaderBody().getEbAnnouncement()).isNotNull();
        assertThat(block("musashi-dijkstra9").getHeader().getHeaderBody().getEbAnnouncement()).isNull();
        assertThat(block("musashi-dijkstra15").getHeader().getHeaderBody().getEbAnnouncement().getSize())
                .isEqualTo(71103);

        Block certifying = block("rc-2026w41-certifying-block");
        assertThat(certifying.getHeader().getHeaderBody().getLeiosCertInBody()).isTrue();
        assertThat(certifying.getLeiosCertificate().getSigners()).isEqualTo("c0");
        assertLeiosSignature(certifying, "rc-2026w41-certifying-block");
        Block announcing = block("rc-2026w41-announcing-block");
        assertThat(certifying.getHeader().getHeaderBody().getPrevHash())
                .isEqualTo(announcing.getHeader().getHeaderBody().getBlockHash());
        assertThat(announcing.getLeiosCertificate()).isNull();
        assertThat(announcing.getHeader().getHeaderBody().getEbAnnouncement()).isNotNull();

        // Every 12-item header's Leios fields, against its bytes read independently: hash and size of each announcement
        for (String name : List.of("musashi-dijkstra1", "musashi-dijkstra2", "musashi-dijkstra5", "musashi-dijkstra8",
                "musashi-dijkstra9", "musashi-dijkstra15", "rc-2026w41-announcing-block",
                "rc-2026w41-certifying-block"))
            assertLeiosHeader(block(name), name);

        HeaderBody tenItems = block("dijkstranet-first-block").getHeader().getHeaderBody();
        assertThat(tenItems.getLeiosCertInBody()).isNull();
        assertThat(tenItems.getEbAnnouncement()).isNull();
        for (String name : List.of("musashi-dijkstra1", "musashi-dijkstra2", "musashi-dijkstra5", "musashi-dijkstra8",
                "musashi-dijkstra9", "musashi-dijkstra15", "rc-2026w41-announcing-block", "rc-2026w41-certifying-block",
                "dijkstranet-first-block"))
            assertThat(block(name).getPerasCertificate()).as(name).isNull();
    }

    /** Synthetic: no vector has a Peras certificate. A byte string in its slot is given as its content in hex. */
    @Test
    void aPerasCertificateIsItsBytesInHex() throws Exception {
        CborSpan original = CborSpan.of(CborLoader.getHexBytes("block/dijkstra/musashi-dijkstra1.txt"));
        CborSpan peras = original.get(1).get(1).get(2);
        assertThat(peras.isNull()).isTrue();
        byte[] bytes = original.replacing(List.of(peras), List.of(HexUtil.decodeHexString("43010203")));
        assertThat(BlockSerializer.INSTANCE.deserialize(bytes).getPerasCertificate()).isEqualTo("010203");
    }

    // ---- helpers ----

    private record Tx(TransactionBody body, Witnesses witnesses, AuxData auxData) {
    }

    /** A sub-transaction body has none of keys 2, 13, 16, 17, 23 and 27 (ADR 0004 D2). */
    private static void assertSubTransactionBody(TransactionBody body) {
        assertThat(body.getFee()).isNull();
        assertThat(body.getCollateralInputs()).isNull();
        assertThat(body.getCollateralReturn()).isNull();
        assertThat(body.getTotalCollateral()).isNull();
        assertThat(body.getSubTransactions()).isNull();
        assertThat(body.getStartingAccountBalanceIntervals()).isNull();
    }

    /** [era, [header, body]] or [header, body], read with {@link IndependentCbor}. */
    private static Object independentBlock(String name) {
        Object block = IndependentCbor.read(CborLoader.getHexBytes("block/dijkstra/" + name + ".txt"));
        return IndependentCbor.get(block, 0) instanceof BigInteger ? IndependentCbor.get(block, 1) : block;
    }

    /** The certificate is [signers, signature], the second item of the block body, and the signature 48 bytes. */
    private static void assertLeiosSignature(Block block, String name) {
        Object certificate = IndependentCbor.get(IndependentCbor.get(independentBlock(name), 1), 1);
        assertThat(block.getLeiosCertificate().getSignature()).as(name)
                .isEqualTo(IndependentCbor.hex(IndependentCbor.get(certificate, 1))).hasSize(2 * 48);
        assertThat(block.getLeiosCertificate().getSigners()).as(name)
                .isEqualTo(IndependentCbor.hex(IndependentCbor.get(certificate, 0)));
    }

    /**
     * A 12-item header body's items 10 and 11: {@code block_body_contains_leios_cert} and
     * {@code eb_references_announcement / nil}, [hash, size].
     */
    private static void assertLeiosHeader(Block block, String name) {
        Object headerBody = IndependentCbor.get(IndependentCbor.get(independentBlock(name), 0), 0);
        assertThat(IndependentCbor.items(headerBody)).as(name).hasSize(12);
        HeaderBody header = block.getHeader().getHeaderBody();
        assertThat(header.getLeiosCertInBody()).as(name).isEqualTo(IndependentCbor.get(headerBody, 10));
        Object announcement = IndependentCbor.get(headerBody, 11);
        if (announcement == IndependentCbor.NULL) {
            assertThat(header.getEbAnnouncement()).as(name).isNull();
            return;
        }
        assertThat(header.getEbAnnouncement().getHash()).as(name)
                .isEqualTo(IndependentCbor.hex(IndependentCbor.get(announcement, 0)));
        assertThat(header.getEbAnnouncement().getSize()).as(name)
                .isEqualTo(((BigInteger) IndependentCbor.get(announcement, 1)).longValueExact());
    }

    private static TopLevelGuard topLevelGuard(TransactionBody body, StakeCredType type) {
        return body.getRequiredTopLevelGuards().stream().filter(guard -> guard.getCredential().getType() == type)
                .findFirst().orElseThrow();
    }

    private static Block block(String name) {
        return BlockSerializer.INSTANCE.deserialize(
                ModelJsonPin.enveloped(CborLoader.getHexBytes("block/dijkstra/" + name + ".txt")));
    }

    /** A transaction, read as the only transaction of musashi-dijkstra2, in the block's form [b, w, aux, true]. */
    private static Tx transaction(String name) throws Exception {
        String hex = HexUtil.encodeHexString(CborLoader.getHexBytes("block/dijkstra/" + name + ".txt"));
        String blockForm = hex.startsWith("83") ? "84" + hex.substring(2) + "f5" : hex;
        CborSpan host = CborSpan.of(CborLoader.getHexBytes("block/dijkstra/musashi-dijkstra2.txt"));
        CborSpan only = host.get(1).get(1).get(0).get(0);
        Block block = BlockSerializer.INSTANCE.deserialize(host.replacing(List.of(only),
                List.of(HexUtil.decodeHexString(blockForm))));
        assertThat(block.getTransactionBodies()).hasSize(1);
        return new Tx(block.getTransactionBodies().get(0), block.getTransactionWitness().get(0),
                block.getAuxiliaryDataMap().get(0));
    }

    private static List<TransactionInput> inputs(CborSpan set) {
        List<TransactionInput> inputs = new ArrayList<>();
        for (CborSpan input : set.untagIf(258).items())
            inputs.add(new TransactionInput(HexUtil.encodeHexString(input.get(0).byteString()),
                    (int) input.get(1).asLong()));
        return inputs;
    }

    private static Credential key(String hash) {
        return Credential.fromKeyHash(HexUtil.decodeHexString(hash));
    }

    private static Credential script(String hash) {
        return Credential.fromScriptHash(HexUtil.decodeHexString(hash));
    }

    /** The ledger's test hash of {@code n}: Blake2b-224 of {@code n} as CBOR, for {@code n} below 2^16. */
    private static String mkKeyHash(int n) {
        String cbor = n < 24 ? String.format("%02x", n) : n < 256 ? String.format("18%02x", n)
                : String.format("19%04x", n);
        return IndependentCbor.blake2b224(HexUtil.decodeHexString(cbor));
    }

    /** The CBOR of a byte string of the given content. */
    private static String byteString(String contentHex) {
        int length = contentHex.length() / 2;
        String header = length < 24 ? String.format("%02x", 0x40 + length)
                : length < 256 ? String.format("58%02x", length) : String.format("59%04x", length);
        return header + contentHex;
    }

    private static String blake2b256(byte[] bytes) {
        return IndependentCbor.blake2b256(bytes);
    }
}
