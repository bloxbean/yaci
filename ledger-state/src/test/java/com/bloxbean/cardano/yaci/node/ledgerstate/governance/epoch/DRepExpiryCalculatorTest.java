package com.bloxbean.cardano.yaci.node.ledgerstate.governance.epoch;

import com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.DRepStateRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for DRepExpiryCalculator using real data from DBSync (preprod).
 * Cross-verified with yaci-store DRepExpiryUtil which matches DBSync exactly.
 * <p>
 * Dormant epochs on preprod (163-232): {163, 172, 173, 174, 175, 197, 204, 205, 206, 207, 223}
 * From yaci-store gov_epoch_activity table (source of truth).
 * drepActivity = 20, govActionLifetime = 6, eraFirstEpoch = 163.
 * <p>
 * Proposals during v9 (for latestProposalUpToRegistration):
 *   id=1: epoch=163, slot=69190815, lifetime=6
 *   id=2: epoch=165, slot=69981572, lifetime=6
 *   id=3: epoch=175, slot=74013957, lifetime=6
 *   id=4: epoch=177, slot=75063375, lifetime=6
 *   id=5: epoch=177, slot=75143783, lifetime=6
 *   id=6: epoch=178, slot=75327829, lifetime=6
 */
class DRepExpiryCalculatorTest {

    private DRepExpiryCalculator calculator;

    private static final Set<Integer> DORMANT_EPOCHS = Set.of(163, 172, 173, 174, 175, 197, 204, 205, 206, 207, 223);
    private static final int DREP_ACTIVITY = 20;
    private static final int ERA_FIRST_EPOCH = 163;
    private static final int GOV_ACTION_LIFETIME = 6;
    private static final int EVALUATED_EPOCH = 232;

    // Proposals from preprod (slot, epoch, lifetime)
    private static final DRepExpiryCalculator.ProposalSubmissionInfo PROP_1 =
            new DRepExpiryCalculator.ProposalSubmissionInfo(69190815, 163, 6);
    private static final DRepExpiryCalculator.ProposalSubmissionInfo PROP_2 =
            new DRepExpiryCalculator.ProposalSubmissionInfo(69981572, 165, 6);
    private static final DRepExpiryCalculator.ProposalSubmissionInfo PROP_3 =
            new DRepExpiryCalculator.ProposalSubmissionInfo(74013957, 175, 6);
    private static final DRepExpiryCalculator.ProposalSubmissionInfo PROP_5 =
            new DRepExpiryCalculator.ProposalSubmissionInfo(75143783, 177, 6);
    private static final DRepExpiryCalculator.ProposalSubmissionInfo PROP_6 =
            new DRepExpiryCalculator.ProposalSubmissionInfo(75327829, 178, 6);
    private static final DRepExpiryCalculator.ProposalSubmissionInfo PROP_7 =
            new DRepExpiryCalculator.ProposalSubmissionInfo(77375189, 182, 6);
    private static final DRepExpiryCalculator.ProposalSubmissionInfo PROP_16 =
            new DRepExpiryCalculator.ProposalSubmissionInfo(83710002, 197, 6);

    @BeforeEach
    void setUp() {
        calculator = new DRepExpiryCalculator();
    }

    private static DRepStateRecord drep(int regEpoch, long regSlot, Integer lastInteractionEpoch,
                                         int protocolVersion) {
        return new DRepStateRecord(
                BigInteger.valueOf(500_000_000_000L), null, null,
                regEpoch, lastInteractionEpoch,
                regEpoch + DREP_ACTIVITY, true, regSlot, protocolVersion, null);
    }

    /**
     * Find the correct latestProposalUpToRegistration for a given slot.
     */
    private static DRepExpiryCalculator.ProposalSubmissionInfo proposalUpToSlot(long slot) {
        DRepExpiryCalculator.ProposalSubmissionInfo[] all = {PROP_1, PROP_2, PROP_3, PROP_5, PROP_6, PROP_7, PROP_16};
        DRepExpiryCalculator.ProposalSubmissionInfo latest = null;
        for (var p : all) {
            if (p.slot() <= slot) {
                if (latest == null || p.slot() > latest.slot()) latest = p;
            }
        }
        return latest;
    }

    // ===== V10 DReps (post-bootstrap, registered epoch 181+) =====
    // V10 formula: expiry = lastActivityEpoch + drepActivity + dormantCount(after lastActivity)
    // No v9 bonus.

    private void assertV10Expiry(int regEpoch, long regSlot, int expected) {
        var state = drep(regEpoch, regSlot, null, 10);
        int expiry = calculator.calculateExpiry(state, DORMANT_EPOCHS, DREP_ACTIVITY,
                ERA_FIRST_EPOCH, EVALUATED_EPOCH, null, GOV_ACTION_LIFETIME);
        assertThat(expiry).as("V10 DRep reg=%d slot=%d", regEpoch, regSlot).isEqualTo(expected);
    }

    @Test
    @DisplayName("V10 DReps: all 21 test cases from DBSync")
    void v10_allCases() {
        assertV10Expiry(200, 84974395, 225);
        assertV10Expiry(203, 86170075, 228);
        assertV10Expiry(203, 86241642, 228);
        assertV10Expiry(206, 87376103, 228);
        assertV10Expiry(206, 87721951, 228);
        assertV10Expiry(207, 88004864, 228);
        assertV10Expiry(208, 88605826, 229);
        assertV10Expiry(199, 84447963, 224);
        assertV10Expiry(199, 84725669, 224);
        assertV10Expiry(194, 82223580, 219);
        assertV10Expiry(194, 82300171, 219);
        assertV10Expiry(190, 80652232, 215);
        assertV10Expiry(190, 80494556, 215);
        assertV10Expiry(190, 80563924, 215);
        assertV10Expiry(190, 80589472, 215);
        assertV10Expiry(188, 79766698, 213);
        assertV10Expiry(185, 78469437, 210);
        assertV10Expiry(185, 78690681, 210);
        assertV10Expiry(182, 77335087, 207);
        assertV10Expiry(181, 76909405, 202);
        assertV10Expiry(181, 76931316, 202);
    }

    @Test
    @DisplayName("V10 DRep with vote interaction at epoch 199 → uses 199 as lastActivity")
    void v10_withVoteInteraction() {
        // c0dc04: reg=199, voted at epoch 199 → lastActivity=199, same as reg
        var state = drep(199, 84447963, 199, 10);
        int expiry = calculator.calculateExpiry(state, DORMANT_EPOCHS, DREP_ACTIVITY,
                ERA_FIRST_EPOCH, EVALUATED_EPOCH, null, GOV_ACTION_LIFETIME);
        assertThat(expiry).isEqualTo(224);
    }

    @Test
    @DisplayName("V10 DRep e8b1ee: reg=190, update at 191 → lastActivity=191")
    void v10_withUpdateInteraction() {
        // e8b1ee: reg=190, last_update=191 → lastActivity=191
        var state = drep(190, 80728581, 191, 10);
        int expiry = calculator.calculateExpiry(state, DORMANT_EPOCHS, DREP_ACTIVITY,
                ERA_FIRST_EPOCH, EVALUATED_EPOCH, null, GOV_ACTION_LIFETIME);
        assertThat(expiry).isEqualTo(216);
    }

    // ===== V9 DReps (bootstrap, registered epoch 163-180) =====
    // V9 formula: expiry = lastActivityEpoch + drepActivity + dormantCount + v9Bonus
    // v9Bonus depends on latestProposalUpToRegistration

    private void assertV9Expiry(int regEpoch, long regSlot, int expected) {
        var latestProposal = proposalUpToSlot(regSlot);
        var state = drep(regEpoch, regSlot, null, 9);
        int expiry = calculator.calculateExpiry(state, DORMANT_EPOCHS, DREP_ACTIVITY,
                ERA_FIRST_EPOCH, EVALUATED_EPOCH, latestProposal, GOV_ACTION_LIFETIME);
        assertThat(expiry).as("V9 DRep reg=%d slot=%d latestProposal=%s", regEpoch, regSlot,
                latestProposal != null ? "epoch=" + latestProposal.epoch() : "null").isEqualTo(expected);
    }

    @Test
    @DisplayName("V9 DReps: all 31 test cases from DBSync")
    void v9_allCases() {
        // Registered at epoch 163 — before any proposals
        assertV9Expiry(163, 69178131, 188);  // 743e34: registered BEFORE PROP_1 (slot 69190815)
        // Registered at epoch 164 — after PROP_1
        assertV9Expiry(164, 69413315, 188);  // a547ab
        assertV9Expiry(164, 69432536, 188);  // 485776
        assertV9Expiry(164, 69499501, 188);  // 245c4f
        assertV9Expiry(164, 69543146, 188);  // 08b4c8
        assertV9Expiry(164, 69545556, 188);  // 563553
        // Registered at epoch 165 — near PROP_2
        assertV9Expiry(165, 69981012, 189);  // 763ef7 (slot < PROP_2.slot)
        assertV9Expiry(165, 69991646, 189);  // 928313
        assertV9Expiry(165, 69855413, 189);  // e04d73
        // Registered at epoch 166
        assertV9Expiry(166, 70482734, 190);  // 8dc91d
        assertV9Expiry(166, 70484697, 190);  // 8b7503
        // Registered at epoch 168
        assertV9Expiry(168, 70950263, 192);  // 60ac97
        assertV9Expiry(168, 71040009, 192);  // 334004
        assertV9Expiry(168, 71259677, 192);  // d114c7
        // Registered at epoch 169
        assertV9Expiry(169, 71527445, 193);  // 8ee224
        assertV9Expiry(169, 71583099, 193);  // a9229e
        // Registered at epoch 171
        assertV9Expiry(171, 72351778, 195);  // b01110
        assertV9Expiry(171, 72374795, 195);  // b17951
        // Registered at epoch 174
        assertV9Expiry(174, 73645931, 199);  // 3ca5cb
        // Registered at epoch 175
        assertV9Expiry(175, 73984021, 200);  // 47c6d3
        // Registered at epoch 176
        assertV9Expiry(176, 74583553, 197);  // f2b5f9
        // Registered at epoch 178
        assertV9Expiry(178, 75303069, 199);  // f0ed00
        assertV9Expiry(178, 75462115, 199);  // 27f2a6
        assertV9Expiry(178, 75610593, 199);  // 0d0dbd
        // Registered at epoch 179
        assertV9Expiry(179, 75758253, 200);  // 5bfef2
        assertV9Expiry(179, 75820112, 200);  // 1943ec
        assertV9Expiry(179, 75927257, 200);  // 7188d8
        assertV9Expiry(179, 76019334, 200);  // 63ace9
        // Registered at epoch 180
        assertV9Expiry(180, 76238410, 201);  // 58b7c6
        assertV9Expiry(180, 76326215, 201);  // 775f55
        assertV9Expiry(180, 76458759, 201);  // dd5866
        assertV9Expiry(180, 76461073, 201);  // d1bffc
    }

    @Test
    @DisplayName("V9 DRep ed11d1: reg=164, voted at 171, updated at 180 → lastActivity=180")
    void v9_withInteractions() {
        // ed11d1: reg=164, last_vote=171, last_update=180 → lastActivity=max(164,180)=180
        var state = drep(164, 69603181, 180, 9);
        var latestProposal = proposalUpToSlot(69603181);
        int expiry = calculator.calculateExpiry(state, DORMANT_EPOCHS, DREP_ACTIVITY,
                ERA_FIRST_EPOCH, EVALUATED_EPOCH, latestProposal, GOV_ACTION_LIFETIME);
        assertThat(expiry).isEqualTo(201);
    }

    @Test
    @DisplayName("V9 DRep d1bffc: reg=180, voted at 180 → lastActivity=180")
    void v9_withVoteAtRegEpoch() {
        // d1bffc: reg=180, last_vote=180
        var state = drep(180, 76461073, 180, 9);
        var latestProposal = proposalUpToSlot(76461073);
        int expiry = calculator.calculateExpiry(state, DORMANT_EPOCHS, DREP_ACTIVITY,
                ERA_FIRST_EPOCH, EVALUATED_EPOCH, latestProposal, GOV_ACTION_LIFETIME);
        assertThat(expiry).isEqualTo(201);
    }

    @Test
    @DisplayName("V9 DRep 763ef7: reg=165, voted at 165 → lastActivity=165, active_until=189")
    void v9_withVoteAtRegEpoch_165() {
        // 763ef7: reg=165, last_vote=165
        var state = drep(165, 69981012, 165, 9);
        var latestProposal = proposalUpToSlot(69981012);
        int expiry = calculator.calculateExpiry(state, DORMANT_EPOCHS, DREP_ACTIVITY,
                ERA_FIRST_EPOCH, EVALUATED_EPOCH, latestProposal, GOV_ACTION_LIFETIME);
        assertThat(expiry).isEqualTo(189);
    }
}
