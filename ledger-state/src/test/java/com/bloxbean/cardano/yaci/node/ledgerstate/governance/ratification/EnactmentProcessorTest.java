package com.bloxbean.cardano.yaci.node.ledgerstate.governance.ratification;

import com.bloxbean.cardano.yaci.core.model.Credential;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredType;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import com.bloxbean.cardano.yaci.core.model.governance.actions.*;
import com.bloxbean.cardano.yaci.core.types.UnitInterval;
import com.bloxbean.cardano.yaci.node.ledgerstate.DefaultAccountStateStore.DeltaOp;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.GovernanceStateStore;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.CommitteeMemberRecord;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.GovActionRecord;
import com.bloxbean.cardano.yaci.node.ledgerstate.test.TestRocksDBHelper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for EnactmentProcessor using real RocksDB.
 * Verifies that ratified governance actions correctly mutate the governance store.
 */
class EnactmentProcessorTest {

    @TempDir Path tempDir;
    private TestRocksDBHelper rocks;
    private GovernanceStateStore store;
    private EnactmentProcessor processor;

    // Real preprod hashes
    static final String COLD1 = "3061a5d942665fc3cac6d38bac91c4f0272c4bc2b353e48633a63747";
    static final String COLD2 = "615b54137e73f090d2dddb04317bee41624f4013e5cfe4a5efa76d76";
    static final String COLD3 = "e36d5e45b277bff4962d6c63be2375af8e68e558e6a373137a0ad6f2";
    static final String HOT1  = "be4b5ca31023088940eb952d01bd365af0c32d13e99e3c06929ef89c";
    static final String COLD_REMOVE = "2f4a6c6f098e20ee4bfd5b39942c164575f8ceb348e754df5d0ec04f";
    static final String TX_HASH = "6f8b70a482e10ae400000000000000000000000000000000000000000000abcd";

    @BeforeEach
    void setUp() throws Exception {
        rocks = TestRocksDBHelper.create(tempDir);
        store = rocks.governanceStore();
        processor = new EnactmentProcessor(store, null); // null paramTracker — tested separately
    }

    @AfterEach
    void tearDown() { rocks.close(); }

    private void commit(WriteBatch batch) throws Exception {
        rocks.db().write(new WriteOptions(), batch);
    }

    private GovActionRecord makeProposal(GovActionType type, com.bloxbean.cardano.yaci.core.model.governance.actions.GovAction action) {
        return new GovActionRecord(BigInteger.valueOf(100_000_000_000L),
                "e04f2a1c17869cdc18372973da9aa7312bb5b8e4e8c4e0dd530b97221b",
                230, 236, type, null, null, action, 97000000L);
    }

    // ===== UpdateCommittee =====

    @Test
    @DisplayName("UpdateCommittee adds new members with correct expiry epochs")
    void updateCommittee_addsNewMembers() throws Exception {
        var newMembers = new LinkedHashMap<Credential, Integer>();
        newMembers.put(new Credential(StakeCredType.ADDR_KEYHASH, COLD1), 242);
        newMembers.put(new Credential(StakeCredType.ADDR_KEYHASH, COLD2), 372);

        var action = UpdateCommittee.builder()
                .newMembersAndTerms(newMembers)
                .threshold(new UnitInterval(BigInteger.valueOf(2), BigInteger.valueOf(3)))
                .build();

        var id = new GovActionId(TX_HASH, 0);
        var proposal = makeProposal(GovActionType.UPDATE_COMMITTEE, action);

        try (WriteBatch batch = new WriteBatch()) {
            processor.enact(id, proposal, 232, batch, new ArrayList<>());
            commit(batch);
        }

        var m1 = store.getCommitteeMember(0, COLD1);
        assertThat(m1).isPresent();
        assertThat(m1.get().expiryEpoch()).isEqualTo(242);

        var m2 = store.getCommitteeMember(0, COLD2);
        assertThat(m2).isPresent();
        assertThat(m2.get().expiryEpoch()).isEqualTo(372);

        // Threshold updated
        var threshold = store.getCommitteeThreshold();
        assertThat(threshold).isPresent();
    }

    @Test
    @DisplayName("UpdateCommittee removes specified members")
    void updateCommittee_removesMembers() throws Exception {
        // Pre-populate a member to remove
        try (WriteBatch batch = new WriteBatch()) {
            store.storeCommitteeMember(0, COLD_REMOVE, CommitteeMemberRecord.noHotKey(229), batch, new ArrayList<>());
            commit(batch);
        }
        assertThat(store.getCommitteeMember(0, COLD_REMOVE)).isPresent();

        var removal = new LinkedHashSet<Credential>();
        removal.add(new Credential(StakeCredType.ADDR_KEYHASH, COLD_REMOVE));

        var action = UpdateCommittee.builder().membersForRemoval(removal).build();
        var id = new GovActionId(TX_HASH, 0);

        try (WriteBatch batch = new WriteBatch()) {
            processor.enact(id, makeProposal(GovActionType.UPDATE_COMMITTEE, action), 232, batch, new ArrayList<>());
            commit(batch);
        }

        assertThat(store.getCommitteeMember(0, COLD_REMOVE)).isEmpty();
    }

    @Test
    @DisplayName("UpdateCommittee preserves existing hot key when adding member")
    void updateCommittee_preservesHotKey() throws Exception {
        // Pre-store a hot key authorization (placeholder from AuthCommitteeHotCert)
        try (WriteBatch batch = new WriteBatch()) {
            store.storeCommitteeMember(0, COLD3, new CommitteeMemberRecord(0, HOT1, 0, false), batch, new ArrayList<>());
            commit(batch);
        }

        // Now enact UpdateCommittee that adds this member with real expiry
        var newMembers = new LinkedHashMap<Credential, Integer>();
        newMembers.put(new Credential(StakeCredType.ADDR_KEYHASH, COLD3), 372);

        var action = UpdateCommittee.builder().newMembersAndTerms(newMembers).build();
        var id = new GovActionId(TX_HASH, 0);

        try (WriteBatch batch = new WriteBatch()) {
            processor.enact(id, makeProposal(GovActionType.UPDATE_COMMITTEE, action), 232, batch, new ArrayList<>());
            commit(batch);
        }

        var member = store.getCommitteeMember(0, COLD3);
        assertThat(member).isPresent();
        assertThat(member.get().expiryEpoch()).isEqualTo(372);
        assertThat(member.get().hasHotKey()).isTrue();
        assertThat(member.get().hotHash()).isEqualTo(HOT1); // preserved!
    }

    // ===== NoConfidence =====

    @Test
    @DisplayName("NoConfidence clears all committee members")
    void noConfidence_clearsCommittee() throws Exception {
        // Pre-populate committee
        try (WriteBatch batch = new WriteBatch()) {
            store.storeCommitteeMember(0, COLD1, CommitteeMemberRecord.noHotKey(242), batch, new ArrayList<>());
            store.storeCommitteeMember(0, COLD2, CommitteeMemberRecord.noHotKey(372), batch, new ArrayList<>());
            commit(batch);
        }
        assertThat(store.getAllCommitteeMembers()).hasSize(2);

        var action = new NoConfidence();
        var id = new GovActionId(TX_HASH, 0);

        try (WriteBatch batch = new WriteBatch()) {
            processor.enact(id, makeProposal(GovActionType.NO_CONFIDENCE, action), 232, batch, new ArrayList<>());
            commit(batch);
        }

        assertThat(store.getAllCommitteeMembers()).isEmpty();
    }

    // ===== TreasuryWithdrawals =====

    @Test
    @DisplayName("TreasuryWithdrawals returns negative treasury delta")
    void treasuryWithdrawals_returnsDelta() throws Exception {
        var withdrawals = new LinkedHashMap<String, BigInteger>();
        withdrawals.put("e0addr1_placeholder_000000000000000000000000000000000000000000000000", BigInteger.valueOf(5_000_000));
        withdrawals.put("e0addr2_placeholder_000000000000000000000000000000000000000000000000", BigInteger.valueOf(3_000_000));

        var action = TreasuryWithdrawalsAction.builder().withdrawals(withdrawals).build();
        var id = new GovActionId(TX_HASH, 0);

        BigInteger delta;
        try (WriteBatch batch = new WriteBatch()) {
            delta = processor.enact(id, makeProposal(GovActionType.TREASURY_WITHDRAWALS_ACTION, action), 232, batch, new ArrayList<>());
            commit(batch);
        }

        assertThat(delta).isEqualTo(BigInteger.valueOf(-8_000_000)); // negative = treasury decreases
    }

    // ===== Last Enacted Action Tracking =====

    @Test
    @DisplayName("Enactment stores lastEnactedAction for the correct purpose type")
    void enactment_storesLastEnactedAction() throws Exception {
        var action = UpdateCommittee.builder().build();
        var id = new GovActionId(TX_HASH, 0);

        try (WriteBatch batch = new WriteBatch()) {
            processor.enact(id, makeProposal(GovActionType.UPDATE_COMMITTEE, action), 232, batch, new ArrayList<>());
            commit(batch);
        }

        // UPDATE_COMMITTEE purpose type is UPDATE_COMMITTEE
        var last = store.getLastEnactedAction(GovActionType.UPDATE_COMMITTEE);
        assertThat(last).isPresent();
        assertThat(last.get().txHash()).isEqualTo(TX_HASH);
        assertThat(last.get().govActionIndex()).isEqualTo(0);
    }

    @Test
    @DisplayName("NoConfidence stores lastEnactedAction as UPDATE_COMMITTEE purpose")
    void noConfidence_storesLastEnactedAsCommitteePurpose() throws Exception {
        var id = new GovActionId(TX_HASH, 0);
        try (WriteBatch batch = new WriteBatch()) {
            processor.enact(id, makeProposal(GovActionType.NO_CONFIDENCE, new NoConfidence()), 232, batch, new ArrayList<>());
            commit(batch);
        }

        // NoConfidence shares UPDATE_COMMITTEE purpose
        var last = store.getLastEnactedAction(GovActionType.UPDATE_COMMITTEE);
        assertThat(last).isPresent();
        assertThat(last.get().txHash()).isEqualTo(TX_HASH);
    }

    @Test
    @DisplayName("TreasuryWithdrawals has no purpose type — no lastEnactedAction stored")
    void treasuryWithdrawals_noPurposeType() throws Exception {
        var action = TreasuryWithdrawalsAction.builder().withdrawals(Map.of()).build();
        var id = new GovActionId(TX_HASH, 0);
        try (WriteBatch batch = new WriteBatch()) {
            processor.enact(id, makeProposal(GovActionType.TREASURY_WITHDRAWALS_ACTION, action), 232, batch, new ArrayList<>());
            commit(batch);
        }

        // No purpose type for TreasuryWithdrawals
        assertThat(store.getLastEnactedAction(GovActionType.TREASURY_WITHDRAWALS_ACTION)).isEmpty();
    }
}
