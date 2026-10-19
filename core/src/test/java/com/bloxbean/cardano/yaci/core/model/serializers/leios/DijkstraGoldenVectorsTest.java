package com.bloxbean.cardano.yaci.core.model.serializers.leios;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.yaci.core.model.*;
import com.bloxbean.cardano.yaci.core.model.certs.PoolRegistration;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredType;
import com.bloxbean.cardano.yaci.core.model.leios.LeiosEndorserBlockRef;
import com.bloxbean.cardano.yaci.core.model.leios.LeiosVote;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockHeaderSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.DijkstraBlockBodySerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.leios.LeiosVoteSerializer;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden vectors from the Haskell node (see {@code src/test/resources/leios/haskell/README.md}): Yaci must read
 * every Dijkstra structure in them. The expected values were read from the same bytes with an independent CBOR
 * decoder.
 */
class DijkstraGoldenVectorsTest {
    private static final String TX_ID = "9cf72928fde97acce7cfaf8202525bb8052f27c4bd9d7c35ddc82c6f270c86f9";
    private static final String SUB_TX_ID = "49059b9f24905f8cc667c21da57cec951a01d7727744ba3474007dc53625260e";
    private static final String KEY_GUARD_1 = "f81ce66e0f52da5ca48193386e7511fde5b030a307b4c3681736c6f0";
    private static final String KEY_GUARD_2 = "5977959ed9df654e13c265eff44e1c8b045a531c577e777ac0dcafe2";
    private static final String SCRIPT_GUARD = "c28625280779716c698cc343cb9f247fe8f466fdfcd17274e61aeae9";

    @Test
    void ledgerExampleTransaction() throws Exception {
        Block block = asEndorserBlockTx(resource("dijkstra-golden-tx.cbor"));
        TransactionBody tx = block.getTransactionBodies().get(0);

        assertThat(tx.getTxHash()).isEqualTo(TX_ID);
        assertThat(tx.getInputs()).hasSize(1);
        assertThat(tx.getOutputs()).hasSize(11);
        assertThat(tx.getFee()).isEqualTo(BigInteger.valueOf(3));

        //14: guards in credential form (#6.258 set); key-hash guards are still required signers
        assertThat(tx.getGuards()).containsExactlyInAnyOrder(
                Credential.builder().type(StakeCredType.ADDR_KEYHASH).hash(KEY_GUARD_1).build(),
                Credential.builder().type(StakeCredType.ADDR_KEYHASH).hash(KEY_GUARD_2).build(),
                Credential.builder().type(StakeCredType.SCRIPTHASH).hash(SCRIPT_GUARD).build());
        assertThat(tx.getRequiredSigners()).containsExactlyInAnyOrder(KEY_GUARD_1, KEY_GUARD_2);

        //25, 26
        assertThat(tx.getDirectDeposits())
                .containsExactly(java.util.Map.entry("e11f9d87172a78f61f248f28ae56b99c091b58236329802d8d48a909c6",
                        BigInteger.valueOf(1000000)));
        assertBalanceIntervals(tx);

        //23: one sub-transaction with its own id, witnesses and auxiliary data
        assertThat(tx.getSubTransactions()).hasSize(1);
        SubTransaction subTx = tx.getSubTransactions().get(0);
        TransactionBody subBody = subTx.getBody();
        assertThat(subBody.getTxHash()).isEqualTo(SUB_TX_ID);
        assertThat(subBody.getGuards()).isNotEmpty();
        assertThat(subBody.getDirectDeposits()).isEqualTo(tx.getDirectDeposits());
        assertBalanceIntervals(subBody);
        assertThat(subBody.getSubTransactions()).isNull();
        assertThat(subTx.getWitnesses()).isNotNull();
        assertThat(subTx.getWitnesses().getVkeyWitnesses()).isNotEmpty();
        assertThat(subTx.getAuxData()).isNotNull();

        //24 (sub-transaction only): {[1, script]: datum, [0, key]: nil}, datum bytes as on chain
        List<RequiredTopLevelGuard> topLevelGuards = subBody.getRequiredTopLevelGuards();
        assertThat(topLevelGuards).hasSize(2);
        assertThat(topLevelGuards.get(0).getCredential())
                .isEqualTo(Credential.builder().type(StakeCredType.SCRIPTHASH).hash(SCRIPT_GUARD).build());
        assertThat(topLevelGuards.get(0).getDatum()).startsWith("d879"); //#6.121(...)
        assertThat(topLevelGuards.get(1).getCredential())
                .isEqualTo(Credential.builder().type(StakeCredType.ADDR_KEYHASH).hash(KEY_GUARD_1).build());
        assertThat(topLevelGuards.get(1).getDatum()).isNull();
        assertThat(tx.getRequiredTopLevelGuards()).isNull();

        //Pool registrations with and without bls_key
        List<PoolRegistration> pools = tx.getCertificates().stream()
                .filter(PoolRegistration.class::isInstance).map(PoolRegistration.class::cast).toList();
        assertThat(pools).hasSize(2);
        PoolParams withBls = pools.stream().map(PoolRegistration::getPoolParams)
                .filter(p -> p.getBlsPublicKey() != null).findFirst().orElseThrow();
        assertThat(withBls.getBlsPublicKey()).hasSize(192).startsWith("a57565542eaa01ef");
        assertThat(withBls.getBlsPossessionProof()).hasSize(96).startsWith("b408288be1abfd4c");
        assertThat(pools).filteredOn(pool -> pool.getPoolParams().getBlsPublicKey() == null).hasSize(1);

        //Redeemer tag 6 (guarding), index 3
        Witnesses witnesses = block.getTransactionWitness().get(0);
        assertThat(witnesses.getRedeemers())
                .anyMatch(r -> r.getTag() == RedeemerTag.Guarding && r.getIndex() == 3);
        assertThat(witnesses.getRedeemers()).noneMatch(r -> r.getTag() == null);

        //Plutus V4: script_ref [4, bytes] in output 10, and aux data key 5
        assertThat(tx.getOutputs().get(10).getScriptRef()).startsWith("8204");
        AuxData auxData = block.getAuxiliaryDataMap().get(0);
        assertThat(auxData.getPlutusV4Scripts()).hasSize(1);
        assertThat(auxData.getPlutusV4Scripts().get(0).getType()).isEqualTo(PlutusScriptType.PlutusScriptV4);
        assertThat(auxData.getPlutusV3Scripts()).hasSize(1);
    }

    @Test
    void consensusGenTxWrapsTheSameTransaction() throws Exception {
        //[7, #6.24(bytes .cbor tx)]: hard-fork era index 7 = Dijkstra
        Array genTx = (Array) CborSerializationUtil.deserializeOne(resource("dijkstra-golden-gentx.cbor"));
        assertThat(CborSerializationUtil.toInt(genTx.getDataItems().get(0)))
                .isEqualTo(com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era.Dijkstra.getValue());
        byte[] tx = ((ByteString) genTx.getDataItems().get(1)).getBytes();
        assertThat(tx).isEqualTo(resource("dijkstra-golden-tx.cbor"));
    }

    @Test
    void consensusHeader() throws Exception {
        DataItem header = CborSerializationUtil.deserializeOne(resource("dijkstra-golden-header.cbor"));
        HeaderBody headerBody = BlockHeaderSerializer.INSTANCE.getBlockHeaderFromHeaderArray((Array) header)
                .getHeaderBody();

        assertThat(headerBody.getBlockNumber()).isEqualTo(3);
        assertThat(headerBody.getSlot()).isEqualTo(9);
        assertThat(headerBody.getBlockBodySize()).isEqualTo(2345);
        assertThat(headerBody.getLeiosCertified()).isTrue();
        assertThat(headerBody.getLeiosAnnouncement()).isNotNull();
        assertThat(headerBody.getLeiosAnnouncement().getEbSize()).isEqualTo(123);
    }

    @Test
    void consensusLeiosVote() throws Exception {
        LeiosVote vote = LeiosVoteSerializer.INSTANCE.deserialize(resource("leios-golden-vote.cbor"));

        assertThat(vote.getAnnouncingRbHash())
                .isEqualTo("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
        assertThat(vote.getVoterId()).isEqualTo(1000);
        assertThat(vote.getVoteSignature()).hasSize(96);
    }

    private static void assertBalanceIntervals(TransactionBody body) {
        var intervals = body.getAccountBalanceIntervals();
        assertThat(intervals).hasSize(3);
        assertThat(intervals.get("f1a9bfee58b8bda1a3df2861735baacb11b594c51dcfe49a4f2a6ea1c4"))
                .isEqualTo(new AccountBalanceInterval(BigInteger.valueOf(100), BigInteger.valueOf(5000), null));
        assertThat(intervals.get("e101a1d395abb1baa33c53d26889d484437301cbba548c0fa0d28b4bd7"))
                .isEqualTo(new AccountBalanceInterval(null, BigInteger.valueOf(10000), null));
        assertThat(intervals.get("e1415082a4d7a407bb3837bca2179336d8f9fa51fc4eecba911ead8407"))
                .isEqualTo(new AccountBalanceInterval(BigInteger.valueOf(500), null, null));
    }

    /** Parse a mempool transaction the way Yaci reads an endorser block transaction. */
    private static Block asEndorserBlockTx(byte[] tx) throws Exception {
        Block certifying = BlockSerializer.INSTANCE.deserialize(HexUtil.decodeHexString(
                Files.readString(Path.of(DijkstraGoldenVectorsTest.class
                        .getResource("/leios/w40a/certifying-block-156613.hex").toURI())).trim()));
        return DijkstraBlockBodySerializer.INSTANCE.mergeEndorserBlockTransactions(certifying,
                LeiosEndorserBlockRef.builder().build(), List.of(tx));
    }

    private static byte[] resource(String name) throws Exception {
        try (InputStream in = DijkstraGoldenVectorsTest.class.getResourceAsStream("/leios/haskell/" + name)) {
            return in.readAllBytes();
        }
    }
}
