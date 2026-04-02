package com.bloxbean.cardano.yaci.node.ledgerstate.governance.epoch;

import com.bloxbean.cardano.yaci.core.model.Credential;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredType;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import com.bloxbean.cardano.yaci.core.model.governance.actions.UpdateCommittee;
import com.bloxbean.cardano.yaci.core.types.UnitInterval;
import com.bloxbean.cardano.yaci.node.ledgerstate.DefaultAccountStateStore.DeltaOp;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.GovernanceStateStore;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.CommitteeMemberRecord;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.GovActionRecord;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.ratification.EnactmentProcessor;
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
 * Tests the two-phase commit mechanism in GovernanceEpochProcessor.
 * <p>
 * Reproduces the critical bug: UpdateCommittee enactment writes new committee members
 * to a WriteBatch, but ratification reads committed state. Without two-phase commit,
 * the new members are invisible to ratification, causing proposals to fail committee checks.
 * <p>
 * This test verifies the fix at the store level: after Phase 1 commit, the new committee
 * members are visible via getAllCommitteeMembers().
 */
class GovernanceTwoPhaseCommitTest {

    @TempDir Path tempDir;
    private TestRocksDBHelper rocks;
    private GovernanceStateStore store;
    private EnactmentProcessor enactmentProcessor;

    // Real preprod committee member hashes (from proposal 81)
    static final String NEW_COLD1 = "615b54137e73f090d2dddb04317bee41624f4013e5cfe4a5efa76d76";
    static final String NEW_COLD2 = "e36d5e45b277bff4962d6c63be2375af8e68e558e6a373137a0ad6f2";
    static final String NEW_COLD3 = "e883ad4599af7b01fe9d01c92bfa405151226db53982931d445b4a96";
    static final String HOT1 = "5bfa7d850280c54110534065fa91a3635bbcc0eadc69c5c792c35e1e";
    static final String HOT2 = "26648e2c6bc4e4863a373bd28d480b2eb788922545e1df357e76a3d2";
    static final String HOT3 = "19ebff7033fe1c1f9cd2b346f2c9b0d7ff28b1527ebed1cc071155b4";

    // Existing committee members (from proposal 23, expiry 242)
    static final String OLD_COLD1 = "3061a5d942665fc3cac6d38bac91c4f0272c4bc2b353e48633a63747";
    static final String OLD_COLD2 = "70d20c66e0d63c9a638d9230310b4fba988f620ab1a41654e66f167c";
    static final String OLD_HOT1 = "be4b5ca31023088940eb952d01bd365af0c32d13e99e3c06929ef89c";
    static final String OLD_HOT2 = "cedf596dbc94e2229ec11118ea9404f2336369c4826219253e4b6802";

    static final String TX_P81 = "6f8b70a482e10ae400000000000000000000000000000000000000000000abcd";

    @BeforeEach
    void setUp() throws Exception {
        rocks = TestRocksDBHelper.create(tempDir);
        store = rocks.governanceStore();
        enactmentProcessor = new EnactmentProcessor(store, null);
    }

    @AfterEach
    void tearDown() { rocks.close(); }

    private void commit(WriteBatch batch) throws Exception {
        rocks.db().write(new WriteOptions(), batch);
    }

    @Test
    @DisplayName("Phase 1 commit makes UpdateCommittee enactment visible to subsequent reads")
    void twoPhaseCommit_committeeVisibleAfterPhase1() throws Exception {
        // Setup: Pre-store existing committee members (from proposal 23, expiry 242)
        try (WriteBatch batch = new WriteBatch()) {
            store.storeCommitteeMember(0, OLD_COLD1, new CommitteeMemberRecord(0, OLD_HOT1, 242, false), batch, new ArrayList<>());
            store.storeCommitteeMember(0, OLD_COLD2, new CommitteeMemberRecord(0, OLD_HOT2, 242, false), batch, new ArrayList<>());
            commit(batch);
        }

        // Pre-store hot key authorizations for new members (AuthCommitteeHotCert at epoch 228)
        // These arrived BEFORE the UpdateCommittee enactment
        try (WriteBatch batch = new WriteBatch()) {
            store.storeCommitteeMember(0, NEW_COLD1, new CommitteeMemberRecord(0, HOT1, 0, false), batch, new ArrayList<>());
            store.storeCommitteeMember(0, NEW_COLD2, new CommitteeMemberRecord(0, HOT2, 0, false), batch, new ArrayList<>());
            store.storeCommitteeMember(0, NEW_COLD3, new CommitteeMemberRecord(0, HOT3, 0, false), batch, new ArrayList<>());
            commit(batch);
        }

        // Store pending enactment for proposal 81 (UpdateCommittee)
        try (WriteBatch batch = new WriteBatch()) {
            store.storePendingEnactment(new GovActionId(TX_P81, 0), batch, new ArrayList<>());
            commit(batch);
        }

        // Build the UpdateCommittee action (adds 3 new members with expiry 372)
        var newMembers = new LinkedHashMap<Credential, Integer>();
        newMembers.put(new Credential(StakeCredType.ADDR_KEYHASH, NEW_COLD1), 372);
        newMembers.put(new Credential(StakeCredType.ADDR_KEYHASH, NEW_COLD2), 372);
        newMembers.put(new Credential(StakeCredType.ADDR_KEYHASH, NEW_COLD3), 372);

        var ucAction = UpdateCommittee.builder()
                .newMembersAndTerms(newMembers)
                .threshold(new UnitInterval(BigInteger.valueOf(2), BigInteger.valueOf(3)))
                .build();

        var proposal = new GovActionRecord(
                BigInteger.valueOf(100_000_000_000L),
                "e04f2a1c17869cdc18372973da9aa7312bb5b8e4e8c4e0dd530b97221b",
                230, 236, GovActionType.UPDATE_COMMITTEE, null, null, ucAction, 97000000L);

        // ===== Phase 1: Enact in a WriteBatch and COMMIT =====
        try (WriteBatch batch = new WriteBatch()) {
            enactmentProcessor.enact(new GovActionId(TX_P81, 0), proposal, 232, batch, new ArrayList<>());
            commit(batch); // KEY: this commit makes changes visible
        }

        // ===== Phase 2: Verify committee members are NOW visible =====
        var allMembers = store.getAllCommitteeMembers();

        // Should see 5 members: 2 old (expiry 242) + 3 new (expiry 372)
        assertThat(allMembers).hasSize(5);

        // New members should have their hot keys preserved from the placeholder
        var member1 = store.getCommitteeMember(0, NEW_COLD1);
        assertThat(member1).isPresent();
        assertThat(member1.get().expiryEpoch()).isEqualTo(372);
        assertThat(member1.get().hasHotKey()).isTrue();
        assertThat(member1.get().hotHash()).isEqualTo(HOT1);

        var member2 = store.getCommitteeMember(0, NEW_COLD2);
        assertThat(member2).isPresent();
        assertThat(member2.get().hotHash()).isEqualTo(HOT2);

        var member3 = store.getCommitteeMember(0, NEW_COLD3);
        assertThat(member3).isPresent();
        assertThat(member3.get().hotHash()).isEqualTo(HOT3);

        // Count active members at epoch 232 (expiryEpoch > 232)
        long activeCount = allMembers.values().stream()
                .filter(m -> !m.resigned() && m.expiryEpoch() > 232)
                .count();
        assertThat(activeCount).isEqualTo(5); // all 5 should be active at epoch 232
    }

    @Test
    @DisplayName("Without Phase 1 commit, new members are NOT visible in uncommitted batch")
    void withoutCommit_membersNotVisible() throws Exception {
        // Pre-store hot key placeholder
        try (WriteBatch batch = new WriteBatch()) {
            store.storeCommitteeMember(0, NEW_COLD1, new CommitteeMemberRecord(0, HOT1, 0, false), batch, new ArrayList<>());
            commit(batch);
        }

        var newMembers = new LinkedHashMap<Credential, Integer>();
        newMembers.put(new Credential(StakeCredType.ADDR_KEYHASH, NEW_COLD1), 372);
        var ucAction = UpdateCommittee.builder().newMembersAndTerms(newMembers).build();

        var proposal = new GovActionRecord(
                BigInteger.valueOf(100_000_000_000L), "e0abc123",
                230, 236, GovActionType.UPDATE_COMMITTEE, null, null, ucAction, 97000000L);

        // Enact in a batch but DON'T commit
        try (WriteBatch batch = new WriteBatch()) {
            enactmentProcessor.enact(new GovActionId(TX_P81, 0), proposal, 232, batch, new ArrayList<>());
            // NO commit here — batch is still uncommitted

            // Reading from RocksDB sees the OLD state (placeholder with expiryEpoch=0)
            var member = store.getCommitteeMember(0, NEW_COLD1);
            assertThat(member).isPresent();
            assertThat(member.get().expiryEpoch()).isEqualTo(0); // still placeholder!
            // The batch has the updated member (expiry=372) but db.get() can't see it

            // After committing, the update becomes visible
            commit(batch);
        }

        var memberAfterCommit = store.getCommitteeMember(0, NEW_COLD1);
        assertThat(memberAfterCommit).isPresent();
        assertThat(memberAfterCommit.get().expiryEpoch()).isEqualTo(372); // now visible
    }
}
