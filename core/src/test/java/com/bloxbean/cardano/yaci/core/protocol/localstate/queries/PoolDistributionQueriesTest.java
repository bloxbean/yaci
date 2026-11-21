package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import com.bloxbean.cardano.yaci.core.model.Credential;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredType;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.N2CVersionData;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2CVersionTableConstant;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Local state queries whose encoding depends on the N2C version (ouroboros-consensus 3.0.1.0 Shelley/Ledger/Query.hs):
 * GetStakeDistribution (5) and GetPoolDistr (21) are supported up to V20, GetStakeDistribution2 (37) and GetPoolDistr2
 * (36) from V21, which answer the ledger's PoolDistr. A Maybe (Set PoolId) argument is [] or [set].
 */
class PoolDistributionQueriesTest {
    private static final AcceptVersion V20 = version(N2CVersionTableConstant.PROTOCOL_V20);
    private static final AcceptVersion V23 = version(N2CVersionTableConstant.PROTOCOL_V23);
    private static final String POOL_A = "aa".repeat(28);
    private static final String POOL_B = "bb".repeat(28);
    private static final String VRF = "cc".repeat(32); //shared by both pools
    private static final String SET_A = "d90102" + "81" + "581c" + POOL_A;

    private static AcceptVersion version(long number) {
        return new AcceptVersion(number, new N2CVersionData(1, false));
    }

    /** [0, [0, [6 (Conway), query]]], the hard-fork wrapper of an era query. */
    private static String wrapped(String query) {
        return "8200" + "8200" + "8206" + query;
    }

    private static String hex(com.bloxbean.cardano.yaci.core.protocol.localstate.api.EraQuery<?> query, AcceptVersion v) {
        return HexUtil.encodeHexString(CborSerializationUtil.serialize(query.serialize(v)));
    }

    @Test
    void stakeDistributionUsesTag37FromV21() {
        assertThat(hex(new StakeDistributionQuery(), V20)).isEqualTo(wrapped("8105"));
        assertThat(hex(new StakeDistributionQuery(), V23)).isEqualTo(wrapped("811825"));
    }

    @Test
    void poolDistrUsesTag36FromV21AndAMaybeSet() {
        assertThat(hex(new PoolDistrQuery(List.of(POOL_A)), V20)).isEqualTo(wrapped("8215" + "81" + SET_A));
        assertThat(hex(new PoolDistrQuery(List.of(POOL_A)), V23)).isEqualTo(wrapped("821824" + "81" + SET_A));
        assertThat(hex(new PoolDistrQuery(null), V23)).isEqualTo(wrapped("821824" + "80")); //every pool
    }

    @Test
    void stakeSnapshotsTakeAMaybeSet() {
        assertThat(hex(new StakeSnapshotQuery(POOL_A), V23)).isEqualTo(wrapped("8214" + "81" + SET_A));
        assertThat(hex(new StakeSnapshotQuery(null), V23)).isEqualTo(wrapped("8214" + "80")); //every pool
    }

    @Test
    void setArgumentsAreSortedAndWithoutDuplicates() {
        String setAB = "d90102" + "82" + "581c" + POOL_A + "581c" + POOL_B;
        assertThat(hex(new PoolDistrQuery(List.of(POOL_B, POOL_A, POOL_B)), V23))
                .isEqualTo(wrapped("821824" + "81" + setAB));
        assertThat(hex(new StakePoolParamsQuery(List.of(POOL_B, POOL_A, POOL_A)), V23))
                .isEqualTo(wrapped("8211" + setAB));

        // a credential's Ord: scripts before key hashes, then the bytes
        Credential keyA = Credential.builder().type(StakeCredType.ADDR_KEYHASH).hash(POOL_A).build();
        Credential keyB = Credential.builder().type(StakeCredType.ADDR_KEYHASH).hash(POOL_B).build();
        Credential scriptB = Credential.builder().type(StakeCredType.SCRIPTHASH).hash(POOL_B).build();
        assertThat(hex(new DRepStateQuery(Era.Conway, List.of(keyB, keyA, scriptB, keyA)), V23))
                .isEqualTo(wrapped("82" + "1819" + "d90102" + "83" + "8201581c" + POOL_B + "8200581c" + POOL_A
                        + "8200581c" + POOL_B));
    }

    @Test
    void oldResultConstructorsAreKept() {
        assertThat(new IndividualPoolStake(POOL_A, BigInteger.ONE, BigInteger.TWO, VRF).getTotalPoolStake()).isNull();
        assertThat(new StakeDistributionQueryResult(java.util.Map.of()).getTotalActiveStake()).isNull();
        assertThat(new PoolDistrQueryResult().getPoolDistributionMap()).isNull();
        assertThat(new StakeSnapshotQueryResult().getPoolStakeSnapshots()).isNull();
    }

    /** [4, [result]]: MsgResult with the era wrapper of a matching era. */
    private static co.nstant.in.cbor.model.DataItem[] msgResult(String result) {
        return CborSerializationUtil.deserialize(HexUtil.decodeHexString("8204" + "81" + result));
    }

    private static String stake(String fraction) {
        return "d81e82" + fraction; //tag 30 rational
    }

    @Test
    void theOldDistributionKeepsEveryPoolEvenWithASharedVrfKey() {
        String result = "a2" + "581c" + POOL_A + "82" + stake("0103") + "5820" + VRF
                + "581c" + POOL_B + "82" + stake("0203") + "5820" + VRF;
        StakeDistributionQueryResult read = new StakeDistributionQuery().deserializeResult(V20, msgResult(result));

        assertThat(read.getStakeDistributionMap()).containsOnlyKeys(POOL_A, POOL_B);
        IndividualPoolStake b = read.getStakeDistributionMap().get(POOL_B);
        assertThat(b.getNumerator()).isEqualTo(2);
        assertThat(b.getDenominator()).isEqualTo(3);
        assertThat(b.getVrfKeyHash()).isEqualTo(VRF);
        assertThat(b.getTotalPoolStake()).isNull();
        assertThat(read.getTotalActiveStake()).isNull();
    }

    @Test
    void theLedgerDistributionHasPoolAndTotalStake() {
        // [{pool => [stake, total pool stake, vrf]}, total active stake]; a fourth (BLS key) item is ignored
        String result = "82" + "a2" + "581c" + POOL_A + "83" + stake("0103") + "1864" + "5820" + VRF
                + "581c" + POOL_B + "84" + stake("0203") + "18c8" + "5820" + VRF + "5830" + "dd".repeat(48)
                + "19012c";
        PoolDistrQueryResult read = new PoolDistrQuery(List.of(POOL_A, POOL_B)).deserializeResult(V23, msgResult(result));

        assertThat(read.getPoolDistributionMap()).containsOnlyKeys(POOL_A, POOL_B);
        assertThat(read.getPoolDistributionMap().get(POOL_A).getTotalPoolStake()).isEqualTo(100);
        assertThat(read.getPoolDistributionMap().get(POOL_B).getTotalPoolStake()).isEqualTo(200);
        assertThat(read.getTotalActiveStake()).isEqualTo(300);
    }

    @Test
    void stakeSnapshotsAreRead() {
        String result = "84" + "a1" + "581c" + POOL_A + "83" + "01" + "02" + "03" + "0a" + "0b" + "0c";
        StakeSnapshotQueryResult read = new StakeSnapshotQuery(POOL_A).deserializeResult(V23, msgResult(result));

        assertThat(read.getPoolStakeSnapshots()).containsOnlyKeys(POOL_A);
        assertThat(read.getPoolStakeSnapshots().get(POOL_A).getGoPool()).isEqualTo(3);
        assertThat(read.getMarkTotal()).isEqualTo(10);
        assertThat(read.getGoTotal()).isEqualTo(12);
    }

    @Test
    void drepStatesAreReadFromAMapWhoseKeysAreNotAList() {
        // {[0, h] => [expiry, anchor?, deposit, delegators]}: CCL's Map returns its keys as a non-List collection
        String result = "a1" + "8200581c" + POOL_A + "84" + "18bc" + "80" + "1a1dcd6500" + "d9010280";
        DRepStateQueryResult read = new DRepStateQuery(Era.Conway, List.of(
                Credential.builder().type(StakeCredType.ADDR_KEYHASH).hash(POOL_A).build()))
                .deserializeResult(V23, msgResult(result));

        assertThat(read.getDRepStates()).singleElement().satisfies(s -> {
            assertThat(s.getDRepHash()).isEqualTo(POOL_A);
            assertThat(s.getExpiry()).isEqualTo(188);
            assertThat(s.getDeposit()).isEqualTo(BigInteger.valueOf(500_000_000));
            assertThat(s.getAnchor()).isNull();
        });
    }
}
