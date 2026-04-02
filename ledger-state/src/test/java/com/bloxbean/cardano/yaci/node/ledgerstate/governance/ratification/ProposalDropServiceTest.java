package com.bloxbean.cardano.yaci.node.ledgerstate.governance.ratification;

import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.GovActionRecord;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.RatificationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ProposalDropService — sibling and descendant proposal dropping
 * after ratification/expiry.
 */
class ProposalDropServiceTest {

    private ProposalDropService dropService;

    private static final BigInteger DEPOSIT = BigInteger.valueOf(100_000_000_000L);

    @BeforeEach
    void setUp() {
        dropService = new ProposalDropService();
    }

    private static GovActionId id(String hash, int idx) {
        return new GovActionId(hash, idx);
    }

    private static GovActionRecord proposal(GovActionType type, String prevTxHash, Integer prevIdx) {
        return new GovActionRecord(DEPOSIT, "e0abc123", 230, 236, type, prevTxHash, prevIdx, null, 97000000L);
    }

    // ===== Sibling Dropping =====

    @Test
    @DisplayName("Ratified proposal drops siblings with same prevAction")
    void ratified_dropsSiblings() {
        var p1 = id("tx1", 0);
        var p2 = id("tx2", 0); // sibling of p1 (same prevAction)
        var p3 = id("tx3", 0); // unrelated (different prevAction)

        Map<GovActionId, GovActionRecord> active = new LinkedHashMap<>();
        active.put(p1, proposal(GovActionType.PARAMETER_CHANGE_ACTION, "prev1", 0));
        active.put(p2, proposal(GovActionType.PARAMETER_CHANGE_ACTION, "prev1", 0)); // same prev
        active.put(p3, proposal(GovActionType.PARAMETER_CHANGE_ACTION, "prev2", 0)); // different prev

        var results = List.of(new RatificationResult(p1, active.get(p1), RatificationResult.Status.RATIFIED));

        Set<GovActionId> dropped = dropService.computeProposalsToDrop(results, active);
        assertThat(dropped).contains(p2);
        assertThat(dropped).doesNotContain(p1, p3);
    }

    @Test
    @DisplayName("NoConfidence and UpdateCommittee share purpose — siblings across types")
    void noConfidenceAndUpdateCommittee_sharePurpose() {
        var p1 = id("tx1", 0); // NoConfidence, ratified
        var p2 = id("tx2", 0); // UpdateCommittee, same prevAction → sibling

        Map<GovActionId, GovActionRecord> active = new LinkedHashMap<>();
        active.put(p1, proposal(GovActionType.NO_CONFIDENCE, null, null));
        active.put(p2, proposal(GovActionType.UPDATE_COMMITTEE, null, null)); // same prevAction (both null)

        var results = List.of(new RatificationResult(p1, active.get(p1), RatificationResult.Status.RATIFIED));

        Set<GovActionId> dropped = dropService.computeProposalsToDrop(results, active);
        assertThat(dropped).contains(p2);
    }

    @Test
    @DisplayName("TreasuryWithdrawals have no purpose chain — no sibling dropping")
    void treasuryWithdrawals_noSiblingDropping() {
        var p1 = id("tx1", 0);
        var p2 = id("tx2", 0);

        Map<GovActionId, GovActionRecord> active = new LinkedHashMap<>();
        active.put(p1, proposal(GovActionType.TREASURY_WITHDRAWALS_ACTION, null, null));
        active.put(p2, proposal(GovActionType.TREASURY_WITHDRAWALS_ACTION, null, null));

        var results = List.of(new RatificationResult(p1, active.get(p1), RatificationResult.Status.RATIFIED));

        Set<GovActionId> dropped = dropService.computeProposalsToDrop(results, active);
        assertThat(dropped).isEmpty();
    }

    // ===== Descendant Dropping =====

    @Test
    @DisplayName("Expired proposal drops its descendants (prevAction chain)")
    void expired_dropsDescendants() {
        var parent = id("txP", 0);
        var child = id("txC", 0);   // prevAction = parent
        var grandchild = id("txGC", 0); // prevAction = child

        Map<GovActionId, GovActionRecord> active = new LinkedHashMap<>();
        active.put(parent, proposal(GovActionType.PARAMETER_CHANGE_ACTION, "genesis", 0));
        active.put(child, proposal(GovActionType.PARAMETER_CHANGE_ACTION, "txP", 0));
        active.put(grandchild, proposal(GovActionType.PARAMETER_CHANGE_ACTION, "txC", 0));

        var results = List.of(new RatificationResult(parent, active.get(parent), RatificationResult.Status.EXPIRED));

        Set<GovActionId> dropped = dropService.computeProposalsToDrop(results, active);
        assertThat(dropped).containsExactlyInAnyOrder(child, grandchild);
        assertThat(dropped).doesNotContain(parent); // expired itself is not in drop set
    }

    @Test
    @DisplayName("Ratified proposal drops siblings AND their descendants")
    void ratified_dropsSiblingsAndDescendants() {
        var ratified = id("txR", 0);
        var sibling = id("txS", 0);        // same prevAction as ratified
        var siblingChild = id("txSC", 0);   // child of sibling

        Map<GovActionId, GovActionRecord> active = new LinkedHashMap<>();
        active.put(ratified, proposal(GovActionType.PARAMETER_CHANGE_ACTION, "prev", 0));
        active.put(sibling, proposal(GovActionType.PARAMETER_CHANGE_ACTION, "prev", 0));
        active.put(siblingChild, proposal(GovActionType.PARAMETER_CHANGE_ACTION, "txS", 0));

        var results = List.of(new RatificationResult(ratified, active.get(ratified), RatificationResult.Status.RATIFIED));

        Set<GovActionId> dropped = dropService.computeProposalsToDrop(results, active);
        assertThat(dropped).containsExactlyInAnyOrder(sibling, siblingChild);
    }

    @Test
    @DisplayName("No proposals dropped when all are ACTIVE")
    void allActive_nothingDropped() {
        var p1 = id("tx1", 0);
        var p2 = id("tx2", 0);

        Map<GovActionId, GovActionRecord> active = new LinkedHashMap<>();
        active.put(p1, proposal(GovActionType.PARAMETER_CHANGE_ACTION, "prev", 0));
        active.put(p2, proposal(GovActionType.PARAMETER_CHANGE_ACTION, "prev", 0));

        var results = List.of(
                new RatificationResult(p1, active.get(p1), RatificationResult.Status.ACTIVE),
                new RatificationResult(p2, active.get(p2), RatificationResult.Status.ACTIVE));

        Set<GovActionId> dropped = dropService.computeProposalsToDrop(results, active);
        assertThat(dropped).isEmpty();
    }

    // ===== Purpose Type Mapping =====

    @Test
    @DisplayName("Purpose type mapping follows Conway spec")
    void purposeTypeMapping() {
        assertThat(ProposalDropService.getPurposeType(GovActionType.NO_CONFIDENCE))
                .isEqualTo(GovActionType.UPDATE_COMMITTEE);
        assertThat(ProposalDropService.getPurposeType(GovActionType.UPDATE_COMMITTEE))
                .isEqualTo(GovActionType.UPDATE_COMMITTEE);
        assertThat(ProposalDropService.getPurposeType(GovActionType.PARAMETER_CHANGE_ACTION))
                .isEqualTo(GovActionType.PARAMETER_CHANGE_ACTION);
        assertThat(ProposalDropService.getPurposeType(GovActionType.HARD_FORK_INITIATION_ACTION))
                .isEqualTo(GovActionType.HARD_FORK_INITIATION_ACTION);
        assertThat(ProposalDropService.getPurposeType(GovActionType.NEW_CONSTITUTION))
                .isEqualTo(GovActionType.NEW_CONSTITUTION);
        assertThat(ProposalDropService.getPurposeType(GovActionType.TREASURY_WITHDRAWALS_ACTION)).isNull();
        assertThat(ProposalDropService.getPurposeType(GovActionType.INFO_ACTION)).isNull();
    }
}
