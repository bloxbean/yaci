package com.bloxbean.cardano.yaci.node.ledgerstate.governance.ratification;

import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.GovActionRecord;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.RatificationResult.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stateless unit tests for RatificationEngine using real data from DBSync (preprod).
 * Tests the core ratification logic: lifecycle, prev-action chain, and per-body threshold checks.
 * <p>
 * No database, no mocks — pure input/output testing.
 */
class RatificationEngineTest {

    // ===== Helper builders =====

    private static GovActionRecord proposal(int submittedEpoch, int expiresAfter,
                                             GovActionType type,
                                             String prevTxHash, Integer prevIdx) {
        return new GovActionRecord(
                new BigInteger("100000000000"), "", submittedEpoch, expiresAfter,
                type, prevTxHash, prevIdx, null, 0L);
    }

    private static ProposalEvaluationInput input(GovActionRecord proposal,
                                                  VoteTallyCalculator.DRepTally drep,
                                                  VoteTallyCalculator.CommitteeTally cc,
                                                  VoteTallyCalculator.SPOTally spo,
                                                  BigDecimal drepThreshold,
                                                  BigDecimal ccThreshold,
                                                  BigDecimal spoThreshold) {
        return new ProposalEvaluationInput(
                new GovActionId("aaaa", 0), proposal,
                drep, cc, spo, drepThreshold, ccThreshold, spoThreshold, BigInteger.ZERO);
    }

    // Common tallies
    private static final VoteTallyCalculator.CommitteeTally CC_7_YES = new VoteTallyCalculator.CommitteeTally(7, 0, 0);
    private static final VoteTallyCalculator.CommitteeTally CC_0_YES = new VoteTallyCalculator.CommitteeTally(0, 0, 0);
    private static final VoteTallyCalculator.CommitteeTally CC_3_YES_4_NO = new VoteTallyCalculator.CommitteeTally(3, 4, 0);
    private static final VoteTallyCalculator.SPOTally SPO_EMPTY = new VoteTallyCalculator.SPOTally(BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO);
    private static final VoteTallyCalculator.SPOTally SPO_PASS = new VoteTallyCalculator.SPOTally(new BigInteger("600"), new BigInteger("400"), BigInteger.ZERO, new BigInteger("1000"));
    private static final VoteTallyCalculator.SPOTally SPO_FAIL = new VoteTallyCalculator.SPOTally(new BigInteger("200"), new BigInteger("800"), BigInteger.ZERO, new BigInteger("1000"));

    private static final Map<GovActionType, GovActionId> NO_PREV = Map.of();
    private static final Map<GovActionType, GovActionId> PREV_PARAM_CHANGE = Map.of(
            GovActionType.PARAMETER_CHANGE_ACTION,
            new GovActionId("b52f02288e3ce8c7e57455522f4edd09c12797749e2db32098ecbe980b645d45", 0));

    // ===== Proposal 82 — ParameterChange (committeeMinSize 7→3) =====

    @Test
    @DisplayName("Proposal 82: should be RATIFIED — committee 7/7 YES, DRep 89% ≥ 0.75")
    void proposal82_ratified() {
        // Real data from DBSync preprod epoch 232
        var drep = new VoteTallyCalculator.DRepTally(
                new BigInteger("34937523403347"),  // YES (05f17c + 81378d)
                new BigInteger("4202886229419"),   // NO (noConfidence + non-voters)
                new BigInteger("291735067393534")); // ABSTAIN
        var proposal = proposal(230, 236, GovActionType.PARAMETER_CHANGE_ACTION,
                "b52f02288e3ce8c7e57455522f4edd09c12797749e2db32098ecbe980b645d45", 0);
        var eval = input(proposal, drep, CC_7_YES, SPO_EMPTY,
                new BigDecimal("0.75"), new BigDecimal("0.51"), BigDecimal.ZERO);

        var status = RatificationEngine.evaluateStateless(
                eval, 232, false, PREV_PARAM_CHANGE, "NORMAL", 7, 146, false);

        assertThat(status).isEqualTo(Status.RATIFIED);
    }

    // ===== Lifecycle tests =====

    @Test
    @DisplayName("Proposal expired: currentEpoch > expiresAfterEpoch + 1 → EXPIRED")
    void expired_proposal() {
        var proposal = proposal(170, 176, GovActionType.PARAMETER_CHANGE_ACTION, null, null);
        var eval = input(proposal, drepAllYes(), CC_7_YES, SPO_EMPTY,
                new BigDecimal("0.67"), new BigDecimal("0.51"), BigDecimal.ZERO);

        var status = RatificationEngine.evaluateStateless(
                eval, 178, false, NO_PREV, "NORMAL", 7, 146, false);

        assertThat(status).isEqualTo(Status.EXPIRED);
    }

    @Test
    @DisplayName("Last chance: currentEpoch == expiresAfterEpoch + 1, not accepted → EXPIRED")
    void lastChance_notAccepted_expired() {
        // DRep fails threshold
        var drep = new VoteTallyCalculator.DRepTally(BigInteger.ONE, new BigInteger("999"), BigInteger.ZERO);
        var proposal = proposal(170, 176, GovActionType.PARAMETER_CHANGE_ACTION, null, null);
        var eval = input(proposal, drep, CC_7_YES, SPO_EMPTY,
                new BigDecimal("0.67"), new BigDecimal("0.51"), BigDecimal.ZERO);

        var status = RatificationEngine.evaluateStateless(
                eval, 177, false, NO_PREV, "NORMAL", 7, 146, false);

        assertThat(status).isEqualTo(Status.EXPIRED);
    }

    @Test
    @DisplayName("Still active: within lifetime, not yet ratified → ACTIVE")
    void withinLifetime_notRatified_active() {
        var drep = new VoteTallyCalculator.DRepTally(BigInteger.ONE, new BigInteger("999"), BigInteger.ZERO);
        var proposal = proposal(170, 176, GovActionType.PARAMETER_CHANGE_ACTION, null, null);
        var eval = input(proposal, drep, CC_7_YES, SPO_EMPTY,
                new BigDecimal("0.67"), new BigDecimal("0.51"), BigDecimal.ZERO);

        var status = RatificationEngine.evaluateStateless(
                eval, 175, false, NO_PREV, "NORMAL", 7, 146, false);

        assertThat(status).isEqualTo(Status.ACTIVE);
    }

    @Test
    @DisplayName("InfoAction can never be ratified → always ACTIVE or EXPIRED")
    void infoAction_neverRatified() {
        var proposal = proposal(170, 176, GovActionType.INFO_ACTION, null, null);
        var eval = input(proposal, drepAllYes(), CC_7_YES, SPO_PASS,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        assertThat(RatificationEngine.evaluateStateless(
                eval, 175, false, NO_PREV, "NORMAL", 7, 146, false))
                .isEqualTo(Status.ACTIVE);

        // On last chance → EXPIRED
        assertThat(RatificationEngine.evaluateStateless(
                eval, 177, false, NO_PREV, "NORMAL", 7, 146, false))
                .isEqualTo(Status.EXPIRED);
    }

    // ===== Previous action chain tests =====

    @Test
    @DisplayName("Prev action valid: matches lastEnacted → ratified")
    void prevAction_valid() {
        var proposal = proposal(230, 236, GovActionType.PARAMETER_CHANGE_ACTION,
                "b52f02288e3ce8c7e57455522f4edd09c12797749e2db32098ecbe980b645d45", 0);
        var eval = input(proposal, drepAllYes(), CC_7_YES, SPO_EMPTY,
                new BigDecimal("0.67"), new BigDecimal("0.51"), BigDecimal.ZERO);

        var status = RatificationEngine.evaluateStateless(
                eval, 232, false, PREV_PARAM_CHANGE, "NORMAL", 7, 146, false);

        assertThat(status).isEqualTo(Status.RATIFIED);
    }

    @Test
    @DisplayName("Prev action invalid: doesn't match lastEnacted → ACTIVE")
    void prevAction_invalid() {
        var proposal = proposal(230, 236, GovActionType.PARAMETER_CHANGE_ACTION,
                "wrong_tx_hash", 0);
        var eval = input(proposal, drepAllYes(), CC_7_YES, SPO_EMPTY,
                new BigDecimal("0.67"), new BigDecimal("0.51"), BigDecimal.ZERO);

        var status = RatificationEngine.evaluateStateless(
                eval, 232, false, PREV_PARAM_CHANGE, "NORMAL", 7, 146, false);

        assertThat(status).isEqualTo(Status.ACTIVE);
    }

    @Test
    @DisplayName("Genesis prev action: both null → valid")
    void prevAction_genesis_bothNull() {
        var proposal = proposal(163, 169, GovActionType.PARAMETER_CHANGE_ACTION, null, null);
        var eval = input(proposal, drepAllYes(), CC_7_YES, SPO_EMPTY,
                BigDecimal.ZERO, new BigDecimal("0.51"), BigDecimal.ZERO);

        var status = RatificationEngine.evaluateStateless(
                eval, 165, true, NO_PREV, "NORMAL", 7, 146, false);

        assertThat(status).isEqualTo(Status.RATIFIED);
    }

    // ===== Committee threshold tests =====

    @Test
    @DisplayName("Committee NO_CONFIDENCE state → committee check fails")
    void committee_noConfidence_fails() {
        var proposal = proposal(230, 236, GovActionType.PARAMETER_CHANGE_ACTION, null, null);
        var eval = input(proposal, drepAllYes(), CC_7_YES, SPO_EMPTY,
                new BigDecimal("0.67"), new BigDecimal("0.51"), BigDecimal.ZERO);

        var status = RatificationEngine.evaluateStateless(
                eval, 232, false, NO_PREV, "NO_CONFIDENCE", 7, 146, false);

        assertThat(status).isEqualTo(Status.ACTIVE);
    }

    @Test
    @DisplayName("Committee below min size → fails")
    void committee_belowMinSize() {
        // 3 YES but minSize=7
        var cc = new VoteTallyCalculator.CommitteeTally(3, 0, 0);
        var proposal = proposal(230, 236, GovActionType.PARAMETER_CHANGE_ACTION, null, null);
        var eval = input(proposal, drepAllYes(), cc, SPO_EMPTY,
                new BigDecimal("0.67"), new BigDecimal("0.51"), BigDecimal.ZERO);

        var status = RatificationEngine.evaluateStateless(
                eval, 232, false, NO_PREV, "NORMAL", 7, 146, false);

        assertThat(status).isEqualTo(Status.ACTIVE);
    }

    @Test
    @DisplayName("Committee threshold not met → fails")
    void committee_thresholdNotMet() {
        var proposal = proposal(230, 236, GovActionType.PARAMETER_CHANGE_ACTION, null, null);
        var eval = input(proposal, drepAllYes(), CC_3_YES_4_NO, SPO_EMPTY,
                new BigDecimal("0.67"), new BigDecimal("0.51"), BigDecimal.ZERO);

        var status = RatificationEngine.evaluateStateless(
                eval, 232, false, NO_PREV, "NORMAL", 3, 146, false);

        assertThat(status).isEqualTo(Status.ACTIVE);
    }

    // ===== DRep threshold tests =====

    @Test
    @DisplayName("DRep threshold met → ratified")
    void drep_thresholdMet() {
        var drep = new VoteTallyCalculator.DRepTally(new BigInteger("700"), new BigInteger("300"), BigInteger.ZERO);
        var proposal = proposal(230, 236, GovActionType.PARAMETER_CHANGE_ACTION, null, null);
        var eval = input(proposal, drep, CC_7_YES, SPO_EMPTY,
                new BigDecimal("0.67"), new BigDecimal("0.51"), BigDecimal.ZERO);

        var status = RatificationEngine.evaluateStateless(
                eval, 232, false, NO_PREV, "NORMAL", 7, 146, false);

        assertThat(status).isEqualTo(Status.RATIFIED);
    }

    @Test
    @DisplayName("DRep threshold not met → ACTIVE")
    void drep_thresholdNotMet() {
        var drep = new VoteTallyCalculator.DRepTally(new BigInteger("300"), new BigInteger("700"), BigInteger.ZERO);
        var proposal = proposal(230, 236, GovActionType.PARAMETER_CHANGE_ACTION, null, null);
        var eval = input(proposal, drep, CC_7_YES, SPO_EMPTY,
                new BigDecimal("0.67"), new BigDecimal("0.51"), BigDecimal.ZERO);

        var status = RatificationEngine.evaluateStateless(
                eval, 232, false, NO_PREV, "NORMAL", 7, 146, false);

        assertThat(status).isEqualTo(Status.ACTIVE);
    }

    @Test
    @DisplayName("Bootstrap phase: DRep check skipped → ratified with committee only")
    void bootstrap_drepSkipped() {
        // DRep would fail, but bootstrap skips DRep check
        var drep = new VoteTallyCalculator.DRepTally(BigInteger.ZERO, new BigInteger("1000"), BigInteger.ZERO);
        var proposal = proposal(163, 169, GovActionType.PARAMETER_CHANGE_ACTION, null, null);
        var eval = input(proposal, drep, CC_7_YES, SPO_EMPTY,
                BigDecimal.ZERO, new BigDecimal("0.51"), BigDecimal.ZERO);

        var status = RatificationEngine.evaluateStateless(
                eval, 165, true, NO_PREV, "NORMAL", 7, 146, false);

        assertThat(status).isEqualTo(Status.RATIFIED);
    }

    // ===== SPO threshold tests =====

    @Test
    @DisplayName("HardFork: SPO required and passes → ratified")
    void hardFork_spoRequired_passes() {
        var proposal = proposal(178, 184, GovActionType.HARD_FORK_INITIATION_ACTION, null, null);
        var eval = input(proposal, drepAllYes(), CC_7_YES, SPO_PASS,
                new BigDecimal("0.60"), new BigDecimal("0.51"), new BigDecimal("0.51"));

        var status = RatificationEngine.evaluateStateless(
                eval, 180, false, NO_PREV, "NORMAL", 7, 146, false);

        assertThat(status).isEqualTo(Status.RATIFIED);
    }

    @Test
    @DisplayName("HardFork: SPO fails → ACTIVE")
    void hardFork_spoFails() {
        var proposal = proposal(178, 184, GovActionType.HARD_FORK_INITIATION_ACTION, null, null);
        var eval = input(proposal, drepAllYes(), CC_7_YES, SPO_FAIL,
                new BigDecimal("0.60"), new BigDecimal("0.51"), new BigDecimal("0.51"));

        var status = RatificationEngine.evaluateStateless(
                eval, 180, false, NO_PREV, "NORMAL", 7, 146, false);

        assertThat(status).isEqualTo(Status.ACTIVE);
    }

    @Test
    @DisplayName("ParameterChange with security params: SPO required")
    void paramChange_securityParams_spoRequired() {
        var proposal = proposal(230, 236, GovActionType.PARAMETER_CHANGE_ACTION, null, null);
        var eval = input(proposal, drepAllYes(), CC_7_YES, SPO_FAIL,
                new BigDecimal("0.67"), new BigDecimal("0.51"), new BigDecimal("0.51")); // SPO threshold > 0

        var status = RatificationEngine.evaluateStateless(
                eval, 232, false, NO_PREV, "NORMAL", 7, 146, false);

        assertThat(status).isEqualTo(Status.ACTIVE); // SPO fails
    }

    @Test
    @DisplayName("ParameterChange without security params: SPO not required")
    void paramChange_noSecurityParams_spoNotRequired() {
        var proposal = proposal(230, 236, GovActionType.PARAMETER_CHANGE_ACTION, null, null);
        var eval = input(proposal, drepAllYes(), CC_7_YES, SPO_FAIL,
                new BigDecimal("0.67"), new BigDecimal("0.51"), BigDecimal.ZERO); // SPO threshold = 0

        var status = RatificationEngine.evaluateStateless(
                eval, 232, false, NO_PREV, "NORMAL", 7, 146, false);

        assertThat(status).isEqualTo(Status.RATIFIED); // SPO not checked
    }

    // ===== NoConfidence and UpdateCommittee =====

    @Test
    @DisplayName("NoConfidence: committee does NOT vote, DRep + SPO required")
    void noConfidence_committeeExcluded() {
        var proposal = proposal(230, 236, GovActionType.NO_CONFIDENCE, null, null);
        // Committee tally irrelevant — NoConfidence skips committee
        var eval = input(proposal, drepAllYes(), CC_0_YES, SPO_PASS,
                new BigDecimal("0.67"), new BigDecimal("0.51"), new BigDecimal("0.51"));

        var status = RatificationEngine.evaluateStateless(
                eval, 232, false, NO_PREV, "NORMAL", 7, 146, false);

        assertThat(status).isEqualTo(Status.RATIFIED);
    }

    @Test
    @DisplayName("UpdateCommittee: committee does NOT vote, DRep + SPO required")
    void updateCommittee_committeeExcluded() {
        var proposal = proposal(229, 235, GovActionType.UPDATE_COMMITTEE, null, null);
        var eval = input(proposal, drepAllYes(), CC_0_YES, SPO_PASS,
                new BigDecimal("0.67"), new BigDecimal("0.51"), new BigDecimal("0.51"));

        var status = RatificationEngine.evaluateStateless(
                eval, 231, false, NO_PREV, "NORMAL", 7, 146, false);

        assertThat(status).isEqualTo(Status.RATIFIED);
    }

    // ===== Delaying action test =====

    @Test
    @DisplayName("Delayed flag set: proposal returns ACTIVE even if all checks pass")
    void delayed_returnsActive() {
        var proposal = proposal(230, 236, GovActionType.PARAMETER_CHANGE_ACTION, null, null);
        var eval = input(proposal, drepAllYes(), CC_7_YES, SPO_EMPTY,
                new BigDecimal("0.67"), new BigDecimal("0.51"), BigDecimal.ZERO);

        var status = RatificationEngine.evaluateStateless(
                eval, 232, false, NO_PREV, "NORMAL", 7, 146, true); // delayed=true

        assertThat(status).isEqualTo(Status.ACTIVE);
    }

    // ===== TreasuryWithdrawal =====

    @Test
    @DisplayName("TreasuryWithdrawal: committee + DRep, no SPO")
    void treasuryWithdrawal_committeeAndDRep() {
        var proposal = proposal(228, 234, GovActionType.TREASURY_WITHDRAWALS_ACTION, null, null);
        var eval = input(proposal, drepAllYes(), CC_7_YES, SPO_EMPTY,
                new BigDecimal("0.67"), new BigDecimal("0.51"), BigDecimal.ZERO);

        var status = RatificationEngine.evaluateStateless(
                eval, 230, false, NO_PREV, "NORMAL", 7, 146, false);

        assertThat(status).isEqualTo(Status.RATIFIED);
    }

    // ===== NewConstitution =====

    @Test
    @DisplayName("NewConstitution: committee + DRep, no SPO")
    void newConstitution_committeeAndDRep() {
        var proposal = proposal(230, 236, GovActionType.NEW_CONSTITUTION, null, null);
        var eval = input(proposal, drepAllYes(), CC_7_YES, SPO_EMPTY,
                new BigDecimal("0.75"), new BigDecimal("0.51"), BigDecimal.ZERO);

        var status = RatificationEngine.evaluateStateless(
                eval, 232, false, NO_PREV, "NORMAL", 7, 146, false);

        assertThat(status).isEqualTo(Status.RATIFIED);
    }

    // ===== Helper =====

    private static VoteTallyCalculator.DRepTally drepAllYes() {
        return new VoteTallyCalculator.DRepTally(new BigInteger("1000"), BigInteger.ZERO, BigInteger.ZERO);
    }
}
