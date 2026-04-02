package com.bloxbean.cardano.yaci.node.ledgerstate.governance;

import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import com.bloxbean.cardano.yaci.node.ledgerstate.DefaultAccountStateStore.DeltaOp;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.CommitteeMemberRecord;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.DRepStateRecord;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.GovActionRecord;
import com.bloxbean.cardano.yaci.node.ledgerstate.test.TestRocksDBHelper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for GovernanceStateStore using real RocksDB.
 * Verifies store/retrieve operations for all governance data types.
 */
class GovernanceStateStoreTest {

    @TempDir Path tempDir;
    private TestRocksDBHelper rocks;
    private GovernanceStateStore store;

    @BeforeEach
    void setUp() throws Exception {
        rocks = TestRocksDBHelper.create(tempDir);
        store = rocks.governanceStore();
    }

    @AfterEach
    void tearDown() {
        rocks.close();
    }

    private void commit(WriteBatch batch) throws RocksDBException {
        rocks.db().write(new org.rocksdb.WriteOptions(), batch);
    }

    // Real preprod committee member hashes for test data
    static final String COLD1 = "3061a5d942665fc3cac6d38bac91c4f0272c4bc2b353e48633a63747";
    static final String COLD2 = "615b54137e73f090d2dddb04317bee41624f4013e5cfe4a5efa76d76";
    static final String COLD3 = "e36d5e45b277bff4962d6c63be2375af8e68e558e6a373137a0ad6f2";
    static final String HOT1  = "be4b5ca31023088940eb952d01bd365af0c32d13e99e3c06929ef89c";
    static final String HOT2  = "5bfa7d850280c54110534065fa91a3635bbcc0eadc69c5c792c35e1e";

    // ===== Committee Members =====

    @Test
    @DisplayName("Store and retrieve committee member with hot key")
    void committeeMember_storeAndRetrieve() throws Exception {
        try (WriteBatch batch = new WriteBatch()) {
            var member = new CommitteeMemberRecord(0, HOT1, 242, false);
            store.storeCommitteeMember(0, COLD1, member, batch, new ArrayList<>());
            commit(batch);
        }

        var result = store.getCommitteeMember(0, COLD1);
        assertThat(result).isPresent();
        assertThat(result.get().hotHash()).isEqualTo(HOT1);
        assertThat(result.get().expiryEpoch()).isEqualTo(242);
        assertThat(result.get().resigned()).isFalse();
        assertThat(result.get().hasHotKey()).isTrue();
    }

    @Test
    @DisplayName("Committee member without hot key")
    void committeeMember_noHotKey() throws Exception {
        try (WriteBatch batch = new WriteBatch()) {
            store.storeCommitteeMember(0, COLD2, CommitteeMemberRecord.noHotKey(300), batch, new ArrayList<>());
            commit(batch);
        }

        var result = store.getCommitteeMember(0, COLD2);
        assertThat(result).isPresent();
        assertThat(result.get().hasHotKey()).isFalse();
        assertThat(result.get().expiryEpoch()).isEqualTo(300);
    }

    @Test
    @DisplayName("getAllCommitteeMembers returns all stored members")
    void committeeMember_getAll() throws Exception {
        try (WriteBatch batch = new WriteBatch()) {
            store.storeCommitteeMember(0, COLD1, new CommitteeMemberRecord(0, HOT1, 242, false), batch, new ArrayList<>());
            store.storeCommitteeMember(0, COLD2, new CommitteeMemberRecord(0, HOT2, 372, false), batch, new ArrayList<>());
            store.storeCommitteeMember(1, COLD3, CommitteeMemberRecord.noHotKey(229), batch, new ArrayList<>());
            commit(batch);
        }

        var all = store.getAllCommitteeMembers();
        assertThat(all).hasSize(3);
    }

    @Test
    @DisplayName("clearAllCommitteeMembers removes all members")
    void committeeMember_clearAll() throws Exception {
        try (WriteBatch batch = new WriteBatch()) {
            store.storeCommitteeMember(0, COLD1, CommitteeMemberRecord.noHotKey(300), batch, new ArrayList<>());
            store.storeCommitteeMember(0, COLD2, CommitteeMemberRecord.noHotKey(300), batch, new ArrayList<>());
            commit(batch);
        }
        assertThat(store.getAllCommitteeMembers()).hasSize(2);

        try (WriteBatch batch = new WriteBatch()) {
            store.clearAllCommitteeMembers(batch, new ArrayList<>());
            commit(batch);
        }
        assertThat(store.getAllCommitteeMembers()).isEmpty();
    }

    // Real preprod DRep hashes
    static final String DREP1 = "03ccae794affbe27a5f5f74da6266002db11daa6ae446aea783b972d";
    static final String DREP2 = "232ab6c11464fcdeb92b69f8f0958c1349b44a732b85248e4371caba";

    // ===== DRep State =====

    @Test
    @DisplayName("Store and retrieve DRep state")
    void drepState_storeAndRetrieve() throws Exception {
        var state = new DRepStateRecord(
                BigInteger.valueOf(500_000_000_000L), "http://example.com", "abc123def456",
                200, null, 220, true, 84974395L, 10, null);
        try (WriteBatch batch = new WriteBatch()) {
            store.storeDRepState(0, DREP1, state, batch, new ArrayList<>());
            commit(batch);
        }

        var result = store.getDRepState(0, DREP1);
        assertThat(result).isPresent();
        assertThat(result.get().registeredAtEpoch()).isEqualTo(200);
        assertThat(result.get().expiryEpoch()).isEqualTo(220);
        assertThat(result.get().active()).isTrue();
        assertThat(result.get().deposit()).isEqualTo(BigInteger.valueOf(500_000_000_000L));
    }

    @Test
    @DisplayName("getAllDRepStates returns all stored DReps")
    void drepState_getAll() throws Exception {
        try (WriteBatch batch = new WriteBatch()) {
            store.storeDRepState(0, DREP1, new DRepStateRecord(
                    BigInteger.valueOf(500_000_000_000L), null, null, 200, null, 220, true, 84974395L, 10, null),
                    batch, new ArrayList<>());
            store.storeDRepState(0, DREP2, new DRepStateRecord(
                    BigInteger.valueOf(500_000_000_000L), null, null, 181, null, 202, true, 76909405L, 10, null),
                    batch, new ArrayList<>());
            commit(batch);
        }

        var all = store.getAllDRepStates();
        assertThat(all).hasSize(2);
    }

    // ===== Proposals =====

    @Test
    @DisplayName("Store and retrieve proposal")
    void proposal_storeAndRetrieve() throws Exception {
        var record = new GovActionRecord(
                BigInteger.valueOf(100_000_000_000L), "e04f2a1c17869cdc18372973da9aa7312bb5b8e4e8c4e0dd530b97221b", 230, 236,
                GovActionType.PARAMETER_CHANGE_ACTION, null, null, null, 97000000L);
        var id = new GovActionId("49578eba0c840e822e0688b09112f3f9baaeb51dd0e346c5a4f9d03d2cbc1953", 0);

        try (WriteBatch batch = new WriteBatch()) {
            store.storeProposal(id, record, batch, new ArrayList<>());
            commit(batch);
        }

        var all = store.getAllActiveProposals();
        assertThat(all).containsKey(id);
        assertThat(all.get(id).deposit()).isEqualTo(BigInteger.valueOf(100_000_000_000L));
        assertThat(all.get(id).actionType()).isEqualTo(GovActionType.PARAMETER_CHANGE_ACTION);
    }

    @Test
    @DisplayName("removeProposal removes from active set")
    void proposal_remove() throws Exception {
        var id = new GovActionId("aff2909f8175ee0200000000000000000000000000000000000000000000abcd", 0);
        var record = new GovActionRecord(
                BigInteger.valueOf(100_000_000_000L), "e04f2a1c17869cdc18372973da9aa7312bb5b8e4e8c4e0dd530b97221b", 230, 236,
                GovActionType.INFO_ACTION, null, null, null, 97000000L);

        try (WriteBatch batch = new WriteBatch()) {
            store.storeProposal(id, record, batch, new ArrayList<>());
            commit(batch);
        }
        assertThat(store.getAllActiveProposals()).containsKey(id);

        try (WriteBatch batch = new WriteBatch()) {
            store.removeProposal(id, batch, new ArrayList<>());
            commit(batch);
        }
        assertThat(store.getAllActiveProposals()).doesNotContainKey(id);
    }

    // ===== Pending Enactments/Drops =====

    @Test
    @DisplayName("Pending enactments survive store → retrieve → clear cycle")
    void pendingEnactments_storeClearCycle() throws Exception {
        var id1 = new GovActionId("b52f02288e3ce8c700000000000000000000000000000000000000000000abcd", 0);
        var id2 = new GovActionId("6f8b70a482e10ae400000000000000000000000000000000000000000000abcd", 0);

        try (WriteBatch batch = new WriteBatch()) {
            store.storePendingEnactment(id1, batch, new ArrayList<>());
            store.storePendingEnactment(id2, batch, new ArrayList<>());
            commit(batch);
        }

        var pending = store.getPendingEnactments();
        assertThat(pending).hasSize(2);

        try (WriteBatch batch = new WriteBatch()) {
            store.clearPending(batch, new ArrayList<>());
            commit(batch);
        }
        assertThat(store.getPendingEnactments()).isEmpty();
        assertThat(store.getPendingDrops()).isEmpty();
    }

    // ===== Dormant Epochs =====

    @Test
    @DisplayName("Dormant epochs store and retrieve")
    void dormantEpochs_storeAndRetrieve() throws Exception {
        Set<Integer> dormant = new HashSet<>(Set.of(163, 172, 173, 174, 175, 197));

        try (WriteBatch batch = new WriteBatch()) {
            store.storeDormantEpochs(dormant, batch, new ArrayList<>());
            commit(batch);
        }

        var result = store.getDormantEpochs();
        assertThat(result).containsExactlyInAnyOrderElementsOf(dormant);
    }

    // ===== Last Enacted Action =====

    @Test
    @DisplayName("Last enacted action per type")
    void lastEnactedAction_storeAndRetrieve() throws Exception {
        try (WriteBatch batch = new WriteBatch()) {
            store.storeLastEnactedAction(GovActionType.PARAMETER_CHANGE_ACTION, "b52f02288e3ce8c700000000000000000000000000000000000000000000abcd", 0, batch, new ArrayList<>());
            store.storeLastEnactedAction(GovActionType.UPDATE_COMMITTEE, "ba588be9a6c9c5ff00000000000000000000000000000000000000000000abcd", 0, batch, new ArrayList<>());
            commit(batch);
        }

        var paramAction = store.getLastEnactedAction(GovActionType.PARAMETER_CHANGE_ACTION);
        assertThat(paramAction).isPresent();
        assertThat(paramAction.get().txHash()).isEqualTo("b52f02288e3ce8c700000000000000000000000000000000000000000000abcd");

        var committeeAction = store.getLastEnactedAction(GovActionType.UPDATE_COMMITTEE);
        assertThat(committeeAction).isPresent();
        assertThat(committeeAction.get().txHash()).isEqualTo("ba588be9a6c9c5ff00000000000000000000000000000000000000000000abcd");

        // Unset type returns empty
        var hfAction = store.getLastEnactedAction(GovActionType.HARD_FORK_INITIATION_ACTION);
        assertThat(hfAction).isEmpty();
    }

    // ===== Donations =====

    @Test
    @DisplayName("Donation accumulation across multiple calls")
    void donations_accumulate() throws Exception {
        try (WriteBatch batch = new WriteBatch()) {
            store.accumulateDonation(239, BigInteger.valueOf(4_000_000), batch, new ArrayList<>());
            commit(batch);
        }
        // Second accumulation in a new batch (simulates next block)
        try (WriteBatch batch = new WriteBatch()) {
            store.accumulateDonation(239, BigInteger.valueOf(8_000_000), batch, new ArrayList<>());
            commit(batch);
        }

        var total = store.getEpochDonations(239);
        assertThat(total).isEqualTo(BigInteger.valueOf(12_000_000));
    }
}
