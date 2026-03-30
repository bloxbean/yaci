package com.bloxbean.cardano.yaci.node.ledgerstate.governance.epoch;

import com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.DRepStateRecord;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Calculates DRep expiry epochs following Conway governance rules.
 * Port of yaci-store's DRepExpiryUtil, cross-verified against Amaru backward_compatibility.rs
 * and Haskell CERTS.updateDormantDRepExpiry.
 * <p>
 * Key formula:
 * <pre>
 * expiry = max(registrationEpoch, lastInteractionEpoch) + drepActivity + dormantCount [+ v9Bonus]
 * </pre>
 */
public class DRepExpiryCalculator {

    /**
     * Calculate the expiry epoch for a DRep at the given epoch boundary.
     *
     * @param state          Current DRep state record
     * @param dormantEpochs  Set of all dormant epoch numbers
     * @param drepActivity   The drepActivity protocol parameter value for this epoch
     * @param eraFirstEpoch  First epoch of the Conway era
     * @param evaluatedEpoch The epoch that just ended (current epoch boundary)
     * @param latestProposalUpToRegistration Info about the latest proposal submitted
     *                                       at or before the DRep's registration slot (null if none)
     * @param govActionLifetime The govActionLifetime protocol parameter at time of latest proposal
     * @return the computed expiry epoch
     */
    public int calculateExpiry(DRepStateRecord state, Set<Integer> dormantEpochs,
                               int drepActivity, int eraFirstEpoch, int evaluatedEpoch,
                               ProposalSubmissionInfo latestProposalUpToRegistration,
                               int govActionLifetime) {
        if (state.protocolVersionAtRegistration() >= 10) {
            return calculateExpiryV10(state, dormantEpochs, drepActivity, evaluatedEpoch);
        } else {
            return calculateExpiryV9(state, dormantEpochs, drepActivity, eraFirstEpoch,
                    evaluatedEpoch, latestProposalUpToRegistration, govActionLifetime);
        }
    }

    /**
     * V10+ expiry: base + dormant count, no v9 bonus.
     */
    int calculateExpiryV10(DRepStateRecord state, Set<Integer> dormantEpochs,
                           int drepActivity, int evaluatedEpoch) {
        int lastActivityEpoch = resolveLastActivityEpoch(state, drepActivity);
        int activityWindow = resolveActivityWindow(state, drepActivity);

        int dormantCount = countDormantInRange(dormantEpochs, lastActivityEpoch, evaluatedEpoch);
        int result = lastActivityEpoch + activityWindow + dormantCount;

        if (result < evaluatedEpoch) {
            result = calculateInactiveExpiry(lastActivityEpoch, activityWindow, 0,
                    evaluatedEpoch, dormantEpochs);
        }
        return result;
    }

    /**
     * V9 expiry: base + dormant count + v9 bonus.
     * The v9 bonus accounts for a Haskell bug where DReps registered during dormant periods
     * receive extra dormant epochs from the Conway era start.
     */
    int calculateExpiryV9(DRepStateRecord state, Set<Integer> dormantEpochs,
                          int drepActivity, int eraFirstEpoch, int evaluatedEpoch,
                          ProposalSubmissionInfo latestProposalUpToRegistration,
                          int govActionLifetime) {
        int lastActivityEpoch = resolveLastActivityEpoch(state, drepActivity);
        int activityWindow = resolveActivityWindow(state, drepActivity);

        int dormantCount = countDormantInRange(dormantEpochs, lastActivityEpoch, evaluatedEpoch);
        int baseExpiry = lastActivityEpoch + activityWindow + dormantCount;

        // Only apply v9 bonus if no post-registration interactions
        int v9Bonus = 0;
        if (state.lastInteractionEpoch() == null) {
            var dormantEpochsToReg = dormantEpochs.stream()
                    .filter(e -> e <= state.registeredAtEpoch())
                    .collect(Collectors.toSet());

            v9Bonus = computeV9Bonus(state.registeredAtEpoch(), state.registeredAtSlot(),
                    latestProposalUpToRegistration, dormantEpochsToReg, eraFirstEpoch);
        }

        int result = baseExpiry + v9Bonus;

        if (result < evaluatedEpoch) {
            result = calculateInactiveExpiry(lastActivityEpoch, activityWindow, v9Bonus,
                    evaluatedEpoch, dormantEpochs);
        }
        return result;
    }

    /**
     * Compute the V9 bonus for DReps registered during the bootstrap phase.
     * <p>
     * From Amaru backward_compatibility.rs (lines 18-71):
     * <ul>
     *   <li>No proposals before registration: bonus = registeredEpoch - eraFirstEpoch + 1</li>
     *   <li>Registered during a dormant gap: bonus = length of that gap</li>
     *   <li>Registered in same epoch as latest proposal AND slot <= proposal slot:
     *       bonus = length of last continuous dormant period up to registration epoch</li>
     *   <li>Otherwise: 0</li>
     * </ul>
     */
    static int computeV9Bonus(int registeredEpoch, long registeredSlot,
                              ProposalSubmissionInfo latestProposal,
                              Set<Integer> dormantEpochsToRegistration,
                              int eraFirstEpoch) {
        if (latestProposal == null) {
            // No proposals before registration
            return registeredEpoch - eraFirstEpoch + 1;
        }

        // Check if there's a dormant gap between proposal expiry and registration
        int gap = registeredEpoch - latestProposal.epoch() - latestProposal.govActionLifetime();

        if (gap > 0) {
            return gap;
        } else if (registeredEpoch == latestProposal.epoch()
                && registeredSlot <= latestProposal.slot()) {
            // Registered in same epoch as proposal, at or before proposal slot
            var lastDormantPeriod = findLastDormantPeriod(dormantEpochsToRegistration, registeredEpoch);
            if (lastDormantPeriod != null) {
                return lastDormantPeriod[1] - lastDormantPeriod[0] + 1;
            }
        }

        return 0;
    }

    /**
     * When a DRep has already expired (expiry < evaluatedEpoch), find the exact epoch
     * when their activity window ran out by walking forward and counting non-dormant epochs.
     */
    static int calculateInactiveExpiry(int lastActionEpoch, int activityWindow,
                                       int v9Bonus, int evaluatedEpoch,
                                       Set<Integer> dormantEpochs) {
        int nonDormantCount = 0;
        for (int epoch = lastActionEpoch + 1; epoch < evaluatedEpoch; epoch++) {
            if (!dormantEpochs.contains(epoch)) {
                nonDormantCount++;
            }
            if (nonDormantCount > activityWindow + v9Bonus) {
                return epoch - 1;
            }
        }
        return evaluatedEpoch - 1;
    }

    // ===== Internal helpers =====

    private int resolveLastActivityEpoch(DRepStateRecord state, int drepActivity) {
        if (state.lastInteractionEpoch() == null) {
            return state.registeredAtEpoch();
        }
        // Use the later of registration epoch or last interaction epoch
        // (re-registration resets lastActivity to the new registration epoch)
        return Math.max(state.registeredAtEpoch(), state.lastInteractionEpoch());
    }

    private int resolveActivityWindow(DRepStateRecord state, int drepActivity) {
        // drepActivity is the protocol parameter — use the value from the relevant epoch
        return drepActivity;
    }

    private static int countDormantInRange(Set<Integer> dormantEpochs,
                                           int lastActivityEpoch, int evaluatedEpoch) {
        int count = 0;
        for (int epoch : dormantEpochs) {
            if (epoch > lastActivityEpoch && epoch <= evaluatedEpoch) {
                count++;
            }
        }
        return count;
    }

    /**
     * Find the last continuous dormant period ending at or before maxEpoch.
     * Returns [start, end] or null if no dormant period found.
     */
    static int[] findLastDormantPeriod(Set<Integer> dormantEpochs, int maxEpoch) {
        if (dormantEpochs.isEmpty()) return null;

        List<Integer> sorted = dormantEpochs.stream()
                .filter(e -> e <= maxEpoch)
                .sorted(Comparator.reverseOrder())
                .toList();

        if (sorted.isEmpty()) return null;

        int end = sorted.get(0);
        int start = end;

        for (int i = 1; i < sorted.size(); i++) {
            int current = sorted.get(i);
            if (current == start - 1) {
                start = current;
            } else {
                break;
            }
        }

        return new int[]{start, end};
    }

    /**
     * Represents a governance proposal submission for v9 bonus calculation.
     *
     * @param slot              Slot when the proposal was submitted
     * @param epoch             Epoch when the proposal was submitted
     * @param govActionLifetime The govActionLifetime protocol parameter at time of proposal
     */
    public record ProposalSubmissionInfo(long slot, int epoch, int govActionLifetime) {}
}
