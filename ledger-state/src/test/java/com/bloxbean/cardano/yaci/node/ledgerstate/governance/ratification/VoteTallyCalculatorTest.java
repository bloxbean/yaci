package com.bloxbean.cardano.yaci.node.ledgerstate.governance.ratification;

import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import com.bloxbean.cardano.yaci.core.model.governance.VoterType;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.GovernanceStateStore;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.epoch.DRepDistributionCalculator.DRepDistKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for VoteTallyCalculator using real data from DBSync (preprod).
 * Verifies DRep, SPO, and Committee vote tallying matches Haskell/Amaru behavior.
 */
class VoteTallyCalculatorTest {

    private VoteTallyCalculator calculator;

    // DRep type constants
    private static final int DREP_KEY = 0;
    private static final int DREP_SCRIPT = 1;
    private static final int DREP_ABSTAIN = 2;
    private static final int DREP_NO_CONF = 3;

    @BeforeEach
    void setUp() {
        calculator = new VoteTallyCalculator();
    }

    /**
     * Proposal 82 (ParameterChange: committeeMinSize 7→3) — ratified at epoch 232.
     * Real data from DBSync preprod.
     *
     * Key facts:
     * - 118 total DReps in distribution (63 active, 55 expired)
     * - 2 DRep YES votes: 05f17c (32.3T) + 81378d (2.6T) = 34.9T YES
     * - ABSTAIN virtual DRep: 291.7T (excluded from denominator)
     * - NO_CONFIDENCE virtual DRep: 286B (counts as NO)
     * - Active non-abstain denominator: ~39.1T
     * - Expected YES ratio: 89.26% — passes 0.75 governance group threshold
     *
     * The critical test: expired DReps MUST be excluded from the tally denominator.
     * Without filtering, denominator would be ~89T and YES ratio drops to ~39%.
     */
    @Test
    void proposal82_drepTally_withActiveFilter_shouldPass075Threshold() {
        // Build DRep distribution (from DBSync drep_distr epoch 232)
        Map<DRepDistKey, BigInteger> drepDist = new HashMap<>();

        // Virtual DReps
        drepDist.put(new DRepDistKey(DREP_ABSTAIN, "abstain"), new BigInteger("291735067393534"));
        drepDist.put(new DRepDistKey(DREP_NO_CONF, "no_confidence"), new BigInteger("286786110244"));

        // YES voters (active)
        drepDist.put(new DRepDistKey(DREP_KEY, "05f17c5238cb888b8bfa2e5e20062d0f870f40f3ea65acb2a7135f8a"),
                new BigInteger("32310000000000"));
        drepDist.put(new DRepDistKey(DREP_KEY, "81378d3ea89b68f0fc09944b1f93f2feb851318f196f98ba4029d8a8"),
                new BigInteger("2627523403347"));

        // A sample of OTHER active DReps (non-voters → count as NO)
        drepDist.put(new DRepDistKey(DREP_KEY, "b835cc20ca747745bb5186843609084a7bbde6dd93504bc71f9e3898"),
                new BigInteger("2737192950725"));
        drepDist.put(new DRepDistKey(DREP_KEY, "cc15d8b518b3af5846c6d398bb6ce37ab955e54fa9a15eed29f5f2e7"),
                new BigInteger("1365649151841"));

        // Expired DReps (active_until < 232) — MUST be excluded from tally
        drepDist.put(new DRepDistKey(DREP_KEY, "8dc91d567324398d028661751322445f3ce6f6139a5cb4b66a9e9576"),
                new BigInteger("220674017443")); // active_until=190
        drepDist.put(new DRepDistKey(DREP_KEY, "5bfef20b43131c5ca870846b561ef4f395add12019e9c7a47f756d44"),
                new BigInteger("21478629220314")); // active_until=200
        drepDist.put(new DRepDistKey(DREP_KEY, "27f2a65f1443db9a8c6ab7a2549d7d34a99d6b00a102cf1dc735fd04"),
                new BigInteger("9294054004969")); // active_until=199

        // Build active DRep keys set (only DReps with active_until >= 232)
        Set<DRepDistKey> activeDRepKeys = new HashSet<>();
        activeDRepKeys.add(new DRepDistKey(DREP_KEY, "05f17c5238cb888b8bfa2e5e20062d0f870f40f3ea65acb2a7135f8a"));
        activeDRepKeys.add(new DRepDistKey(DREP_KEY, "81378d3ea89b68f0fc09944b1f93f2feb851318f196f98ba4029d8a8"));
        activeDRepKeys.add(new DRepDistKey(DREP_KEY, "b835cc20ca747745bb5186843609084a7bbde6dd93504bc71f9e3898"));
        activeDRepKeys.add(new DRepDistKey(DREP_KEY, "cc15d8b518b3af5846c6d398bb6ce37ab955e54fa9a15eed29f5f2e7"));
        // Expired DReps (8dc91d, 5bfef2, 27f2a6) are NOT in activeDRepKeys

        // Build votes (DRep YES votes for proposal 82)
        Map<GovernanceStateStore.VoterKey, Integer> votes = new HashMap<>();
        votes.put(new GovernanceStateStore.VoterKey(VoterType.DREP_KEY_HASH.ordinal(),
                "05f17c5238cb888b8bfa2e5e20062d0f870f40f3ea65acb2a7135f8a"), 1); // YES
        votes.put(new GovernanceStateStore.VoterKey(VoterType.DREP_KEY_HASH.ordinal(),
                "81378d3ea89b68f0fc09944b1f93f2feb851318f196f98ba4029d8a8"), 1); // YES

        // Compute tally WITH active filter (correct behavior)
        var tally = calculator.computeDRepTally(votes, drepDist, GovActionType.PARAMETER_CHANGE_ACTION, activeDRepKeys);

        // Verify: YES should include both voters' stake
        BigInteger expectedYes = new BigInteger("34937523403347"); // 32310000000000 + 2627523403347
        assertThat(tally.yesStake()).isEqualTo(expectedYes);

        // Verify: Abstain excluded from denominator
        assertThat(tally.abstainStake()).isEqualTo(new BigInteger("291735067393534"));

        // Verify: expired DReps NOT in NO stake
        // NO = noConfidence (286786110244) + active non-voters (b835 + cc15)
        BigInteger expectedNo = new BigInteger("286786110244")
                .add(new BigInteger("2737192950725"))
                .add(new BigInteger("1365649151841"));
        assertThat(tally.noStake()).isEqualTo(expectedNo);

        // Verify threshold passes
        BigDecimal threshold = new BigDecimal("0.75");
        assertThat(VoteTallyCalculator.drepThresholdMet(tally, threshold)).isTrue();
    }

    /**
     * Same data but WITHOUT active filter — simulates the bug where expired DReps
     * inflate the denominator, causing the threshold check to fail.
     */
    @Test
    void proposal82_drepTally_withoutActiveFilter_shouldFail075Threshold() {
        Map<DRepDistKey, BigInteger> drepDist = new HashMap<>();
        drepDist.put(new DRepDistKey(DREP_ABSTAIN, "abstain"), new BigInteger("291735067393534"));
        drepDist.put(new DRepDistKey(DREP_NO_CONF, "no_confidence"), new BigInteger("286786110244"));
        drepDist.put(new DRepDistKey(DREP_KEY, "05f17c5238cb888b8bfa2e5e20062d0f870f40f3ea65acb2a7135f8a"),
                new BigInteger("32310000000000"));
        drepDist.put(new DRepDistKey(DREP_KEY, "81378d3ea89b68f0fc09944b1f93f2feb851318f196f98ba4029d8a8"),
                new BigInteger("2627523403347"));
        drepDist.put(new DRepDistKey(DREP_KEY, "b835cc20ca747745bb5186843609084a7bbde6dd93504bc71f9e3898"),
                new BigInteger("2737192950725"));
        // Expired DReps included
        drepDist.put(new DRepDistKey(DREP_KEY, "5bfef20b43131c5ca870846b561ef4f395add12019e9c7a47f756d44"),
                new BigInteger("21478629220314"));
        drepDist.put(new DRepDistKey(DREP_KEY, "27f2a65f1443db9a8c6ab7a2549d7d34a99d6b00a102cf1dc735fd04"),
                new BigInteger("9294054004969"));

        Map<GovernanceStateStore.VoterKey, Integer> votes = new HashMap<>();
        votes.put(new GovernanceStateStore.VoterKey(VoterType.DREP_KEY_HASH.ordinal(),
                "05f17c5238cb888b8bfa2e5e20062d0f870f40f3ea65acb2a7135f8a"), 1);
        votes.put(new GovernanceStateStore.VoterKey(VoterType.DREP_KEY_HASH.ordinal(),
                "81378d3ea89b68f0fc09944b1f93f2feb851318f196f98ba4029d8a8"), 1);

        // Compute tally WITHOUT active filter (null = count ALL DReps)
        var tally = calculator.computeDRepTally(votes, drepDist, GovActionType.PARAMETER_CHANGE_ACTION, null);

        // Expired DReps inflate the NO stake → threshold fails
        BigDecimal threshold = new BigDecimal("0.75");
        assertThat(VoteTallyCalculator.drepThresholdMet(tally, threshold)).isFalse();
    }

    /**
     * NoConfidence virtual DRep should vote YES on NoConfidence proposals
     * and NO on everything else.
     */
    @Test
    void noConfidenceDRep_votesYes_onlyForNoConfidenceProposals() {
        Map<DRepDistKey, BigInteger> drepDist = new HashMap<>();
        drepDist.put(new DRepDistKey(DREP_NO_CONF, "no_confidence"), new BigInteger("1000000000"));
        drepDist.put(new DRepDistKey(DREP_ABSTAIN, "abstain"), BigInteger.ZERO);

        Map<GovernanceStateStore.VoterKey, Integer> votes = new HashMap<>();

        // For NoConfidence action → NoConfidence DRep counts as YES
        var tallyNC = calculator.computeDRepTally(votes, drepDist, GovActionType.NO_CONFIDENCE, null);
        assertThat(tallyNC.yesStake()).isEqualTo(new BigInteger("1000000000"));
        assertThat(tallyNC.noStake()).isEqualTo(BigInteger.ZERO);

        // For ParameterChange → NoConfidence DRep counts as NO
        var tallyPC = calculator.computeDRepTally(votes, drepDist, GovActionType.PARAMETER_CHANGE_ACTION, null);
        assertThat(tallyPC.yesStake()).isEqualTo(BigInteger.ZERO);
        assertThat(tallyPC.noStake()).isEqualTo(new BigInteger("1000000000"));
    }

    /**
     * Abstain DRep stake should be excluded from denominator.
     * With only abstain stake and YES voters, denominator = YES → ratio = 100%.
     */
    @Test
    void abstainDRep_excludedFromDenominator() {
        Map<DRepDistKey, BigInteger> drepDist = new HashMap<>();
        drepDist.put(new DRepDistKey(DREP_ABSTAIN, "abstain"), new BigInteger("999999999999999"));
        drepDist.put(new DRepDistKey(DREP_KEY, "voter1"), new BigInteger("1000"));

        Set<DRepDistKey> active = Set.of(new DRepDistKey(DREP_KEY, "voter1"));

        Map<GovernanceStateStore.VoterKey, Integer> votes = new HashMap<>();
        votes.put(new GovernanceStateStore.VoterKey(VoterType.DREP_KEY_HASH.ordinal(), "voter1"), 1);

        var tally = calculator.computeDRepTally(votes, drepDist, GovActionType.PARAMETER_CHANGE_ACTION, active);
        assertThat(tally.yesStake()).isEqualTo(new BigInteger("1000"));
        assertThat(tally.abstainStake()).isEqualTo(new BigInteger("999999999999999"));

        // Even though abstain has 999T, YES/denominator = 1000/1000 = 100%
        assertThat(VoteTallyCalculator.drepThresholdMet(tally, new BigDecimal("0.99"))).isTrue();
    }

    /**
     * Committee tally: members without hot keys are excluded.
     * Members who voted abstain excluded from denominator.
     * Zero denominator → fail (no quorum).
     */
    @Test
    void committeeTally_zeroEligibleMembers_shouldFail() {
        Map<GovernanceStateStore.VoterKey, Integer> votes = new HashMap<>();
        // No committee votes

        // All members expired
        Map<GovernanceStateStore.CredentialKey,
                com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.CommitteeMemberRecord> members = new HashMap<>();
        members.put(new GovernanceStateStore.CredentialKey(0, "member1"),
                new com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.CommitteeMemberRecord(
                        0, "hot1", 200, false)); // expired at 200, current epoch 232

        var tally = calculator.computeCommitteeTally(votes, members, 232);
        // Zero denominator → fail
        assertThat(VoteTallyCalculator.committeeThresholdMet(tally, new BigDecimal("0.51"))).isFalse();
    }
}
