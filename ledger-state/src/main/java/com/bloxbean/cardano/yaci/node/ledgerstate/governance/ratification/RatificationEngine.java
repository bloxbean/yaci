package com.bloxbean.cardano.yaci.node.ledgerstate.governance.ratification;

import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.GovernanceStateStore;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.GovernanceStateStore.CredentialKey;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.epoch.DRepDistributionCalculator.DRepDistKey;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.CommitteeMemberRecord;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.GovActionRecord;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.RatificationResult;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.RatificationResult.Status;
import org.rocksdb.RocksDBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

/**
 * Core ratification logic — evaluates all active proposals against voting thresholds.
 * Port of yaci-store GovernanceEvaluationService + per-action evaluators.
 * <p>
 * Proposals are evaluated in priority order. Delaying actions (if ratified) prevent
 * subsequent lower-priority actions from being ratified in the same epoch.
 */
public class RatificationEngine {
    private static final Logger log = LoggerFactory.getLogger(RatificationEngine.class);

    private final GovernanceStateStore governanceStore;
    private final VoteTallyCalculator tallyCalculator;

    public RatificationEngine(GovernanceStateStore governanceStore,
                              VoteTallyCalculator tallyCalculator) {
        this.governanceStore = governanceStore;
        this.tallyCalculator = tallyCalculator;
    }

    /**
     * Evaluate all active proposals for the epoch boundary.
     *
     * @param activeProposals    All currently active proposals
     * @param drepDist           DRep distribution (from DRepDistributionCalculator)
     * @param poolStakeDist      Pool → active stake
     * @param poolDRepDelegation Pool → DRep delegation type (for SPO default votes)
     * @param committeeMembers   Committee member states
     * @param committeeThreshold Committee quorum threshold
     * @param lastEnactedActions Last enacted action per type
     * @param currentEpoch       Current epoch (at boundary)
     * @param isBootstrapPhase   Whether in protocol v9 bootstrap
     * @param committeeMinSize   Min committee size (post-bootstrap check)
     * @param committeeState     "NORMAL" or "NO_CONFIDENCE"
     * @param treasury           Current treasury balance (for treasury withdrawal check)
     * @param drepThresholds     DRep voting thresholds per action type
     * @param spoThresholds      SPO voting thresholds per action type
     * @return List of ratification results per proposal
     */
    public List<RatificationResult> evaluateAll(
            Map<GovActionId, GovActionRecord> activeProposals,
            Map<DRepDistKey, BigInteger> drepDist,
            Map<String, BigInteger> poolStakeDist,
            Map<String, Integer> poolDRepDelegation,
            Map<CredentialKey, CommitteeMemberRecord> committeeMembers,
            BigDecimal committeeThreshold,
            Map<GovActionType, GovActionId> lastEnactedActions,
            int currentEpoch,
            boolean isBootstrapPhase,
            int committeeMinSize,
            int committeeMaxTermLength,
            String committeeState,
            BigInteger treasury,
            Map<GovActionType, BigDecimal> drepThresholds,
            Map<GovActionType, BigDecimal> spoThresholds) throws RocksDBException {

        // Sort proposals by priority (lower = higher priority) then by slot
        List<Map.Entry<GovActionId, GovActionRecord>> sorted = new ArrayList<>(activeProposals.entrySet());
        sorted.sort(Comparator.comparingInt((Map.Entry<GovActionId, GovActionRecord> e) ->
                        getActionPriority(e.getValue().actionType()))
                .thenComparingLong(e -> e.getValue().proposalSlot()));

        List<RatificationResult> results = new ArrayList<>();
        boolean delayed = false;

        for (var entry : sorted) {
            GovActionId id = entry.getKey();
            GovActionRecord proposal = entry.getValue();

            Status status = evaluateProposal(id, proposal, drepDist, poolStakeDist, poolDRepDelegation,
                    committeeMembers, committeeThreshold, lastEnactedActions, currentEpoch,
                    isBootstrapPhase, committeeMinSize, committeeMaxTermLength,
                    committeeState, treasury, drepThresholds, spoThresholds, delayed);

            results.add(new RatificationResult(id, proposal, status));

            // Delaying actions prevent subsequent ratifications
            if (status == Status.RATIFIED && isDelayingAction(proposal.actionType())) {
                delayed = true;
            }
        }

        long ratified = results.stream().filter(RatificationResult::isRatified).count();
        long expired = results.stream().filter(RatificationResult::isExpired).count();
        log.info("Ratification results: {} ratified, {} expired, {} active (of {} total)",
                ratified, expired, results.size() - ratified - expired, results.size());

        return results;
    }

    /**
     * Evaluate a single proposal.
     */
    private Status evaluateProposal(
            GovActionId id, GovActionRecord proposal,
            Map<DRepDistKey, BigInteger> drepDist,
            Map<String, BigInteger> poolStakeDist,
            Map<String, Integer> poolDRepDelegation,
            Map<CredentialKey, CommitteeMemberRecord> committeeMembers,
            BigDecimal committeeThreshold,
            Map<GovActionType, GovActionId> lastEnactedActions,
            int currentEpoch, boolean isBootstrapPhase,
            int committeeMinSize, int committeeMaxTermLength,
            String committeeState,
            BigInteger treasury,
            Map<GovActionType, BigDecimal> drepThresholds,
            Map<GovActionType, BigDecimal> spoThresholds,
            boolean delayed) throws RocksDBException {

        GovActionType type = proposal.actionType();

        // Lifecycle check (per yaci-store RatificationContext):
        // expiresAfterEpoch = proposedInEpoch + govActionLifetime = maxAllowedVotingEpoch
        // isOutOfLifecycle: (currentEpoch - expiresAfterEpoch) > 1  → currentEpoch > expiresAfterEpoch + 1
        // isLastRatificationOpportunity: (currentEpoch - expiresAfterEpoch) == 1  → currentEpoch == expiresAfterEpoch + 1
        boolean isExpired = (currentEpoch - proposal.expiresAfterEpoch()) > 1;
        boolean isLastChance = (currentEpoch - proposal.expiresAfterEpoch()) == 1;

        if (isExpired) return Status.EXPIRED;

        // InfoAction can never be ratified
        if (type == GovActionType.INFO_ACTION) {
            return isLastChance ? Status.EXPIRED : Status.ACTIVE;
        }

        // If delayed by a prior delaying action this epoch
        if (delayed) {
            return isLastChance ? Status.EXPIRED : Status.ACTIVE;
        }

        // Prev action check (for chained action types)
        if (!prevActionValid(proposal, lastEnactedActions)) {
            return isLastChance ? Status.EXPIRED : Status.ACTIVE;
        }

        // Get votes for this proposal
        Map<GovernanceStateStore.VoterKey, Integer> votes =
                governanceStore.getVotesForProposal(id.getTransactionId(), id.getGov_action_index());

        // Evaluate per action type
        boolean accepted = switch (type) {
            case HARD_FORK_INITIATION_ACTION -> evaluateHardFork(
                    votes, drepDist, poolStakeDist, poolDRepDelegation,
                    committeeMembers, committeeThreshold, committeeState,
                    currentEpoch, isBootstrapPhase, committeeMinSize,
                    drepThresholds, spoThresholds);

            case PARAMETER_CHANGE_ACTION -> evaluateParameterChange(
                    votes, drepDist, poolStakeDist, poolDRepDelegation,
                    committeeMembers, committeeThreshold, committeeState,
                    currentEpoch, isBootstrapPhase, committeeMinSize,
                    drepThresholds, spoThresholds);

            case TREASURY_WITHDRAWALS_ACTION -> evaluateTreasuryWithdrawal(
                    votes, drepDist, committeeMembers, committeeThreshold,
                    committeeState, currentEpoch, isBootstrapPhase,
                    committeeMinSize, treasury, proposal, drepThresholds);

            case NO_CONFIDENCE -> evaluateNoConfidence(
                    votes, drepDist, poolStakeDist, poolDRepDelegation,
                    currentEpoch, isBootstrapPhase, drepThresholds, spoThresholds);

            case UPDATE_COMMITTEE -> evaluateUpdateCommittee(
                    votes, drepDist, poolStakeDist, poolDRepDelegation,
                    currentEpoch, isBootstrapPhase, committeeState,
                    committeeMaxTermLength, proposal,
                    drepThresholds, spoThresholds);

            case NEW_CONSTITUTION -> evaluateNewConstitution(
                    votes, drepDist, committeeMembers, committeeThreshold,
                    committeeState, currentEpoch, isBootstrapPhase,
                    committeeMinSize, drepThresholds);

            case INFO_ACTION -> false; // Already handled above
        };

        if (accepted) return Status.RATIFIED;
        return isLastChance ? Status.EXPIRED : Status.ACTIVE;
    }

    // ===== Per-Action-Type Evaluators =====

    private boolean evaluateHardFork(
            Map<GovernanceStateStore.VoterKey, Integer> votes,
            Map<DRepDistKey, BigInteger> drepDist,
            Map<String, BigInteger> poolStakeDist,
            Map<String, Integer> poolDRepDelegation,
            Map<CredentialKey, CommitteeMemberRecord> committeeMembers,
            BigDecimal committeeThreshold, String committeeState,
            int currentEpoch, boolean isBootstrapPhase, int committeeMinSize,
            Map<GovActionType, BigDecimal> drepThresholds,
            Map<GovActionType, BigDecimal> spoThresholds) {

        if (!checkCommittee(votes, committeeMembers, committeeThreshold, committeeState,
                currentEpoch, isBootstrapPhase, committeeMinSize)) return false;
        if (!checkSPO(votes, poolStakeDist, poolDRepDelegation,
                GovActionType.HARD_FORK_INITIATION_ACTION, isBootstrapPhase, spoThresholds)) return false;
        if (!isBootstrapPhase && !checkDRep(votes, drepDist,
                GovActionType.HARD_FORK_INITIATION_ACTION, drepThresholds)) return false;
        return true;
    }

    private boolean evaluateParameterChange(
            Map<GovernanceStateStore.VoterKey, Integer> votes,
            Map<DRepDistKey, BigInteger> drepDist,
            Map<String, BigInteger> poolStakeDist,
            Map<String, Integer> poolDRepDelegation,
            Map<CredentialKey, CommitteeMemberRecord> committeeMembers,
            BigDecimal committeeThreshold, String committeeState,
            int currentEpoch, boolean isBootstrapPhase, int committeeMinSize,
            Map<GovActionType, BigDecimal> drepThresholds,
            Map<GovActionType, BigDecimal> spoThresholds) {

        // Committee always required
        if (!checkCommittee(votes, committeeMembers, committeeThreshold, committeeState,
                currentEpoch, isBootstrapPhase, committeeMinSize)) return false;

        if (isBootstrapPhase) return true; // Bootstrap: committee only

        // Post-bootstrap: DRep always required
        if (!checkDRep(votes, drepDist, GovActionType.PARAMETER_CHANGE_ACTION, drepThresholds)) return false;

        // SPO required if security params involved
        // For simplicity, check SPO with security threshold if available
        BigDecimal spoThreshold = spoThresholds.get(GovActionType.PARAMETER_CHANGE_ACTION);
        if (spoThreshold != null && spoThreshold.compareTo(BigDecimal.ZERO) > 0) {
            if (!checkSPO(votes, poolStakeDist, poolDRepDelegation,
                    GovActionType.PARAMETER_CHANGE_ACTION, isBootstrapPhase, spoThresholds)) return false;
        }

        return true;
    }

    private boolean evaluateTreasuryWithdrawal(
            Map<GovernanceStateStore.VoterKey, Integer> votes,
            Map<DRepDistKey, BigInteger> drepDist,
            Map<CredentialKey, CommitteeMemberRecord> committeeMembers,
            BigDecimal committeeThreshold, String committeeState,
            int currentEpoch, boolean isBootstrapPhase, int committeeMinSize,
            BigInteger treasury, GovActionRecord proposal,
            Map<GovActionType, BigDecimal> drepThresholds) {

        if (!checkCommittee(votes, committeeMembers, committeeThreshold, committeeState,
                currentEpoch, isBootstrapPhase, committeeMinSize)) return false;
        if (!isBootstrapPhase && !checkDRep(votes, drepDist,
                GovActionType.TREASURY_WITHDRAWALS_ACTION, drepThresholds)) return false;

        // Treasury balance check: totalWithdrawal <= treasury
        if (proposal.govAction() instanceof com.bloxbean.cardano.yaci.core.model.governance.actions.TreasuryWithdrawalsAction twa) {
            BigInteger totalWithdrawal = BigInteger.ZERO;
            if (twa.getWithdrawals() != null) {
                for (BigInteger amount : twa.getWithdrawals().values()) {
                    totalWithdrawal = totalWithdrawal.add(amount);
                }
            }
            if (totalWithdrawal.compareTo(treasury) > 0) {
                return false; // Cannot withdraw more than treasury balance
            }
        }
        return true;
    }

    private boolean evaluateNoConfidence(
            Map<GovernanceStateStore.VoterKey, Integer> votes,
            Map<DRepDistKey, BigInteger> drepDist,
            Map<String, BigInteger> poolStakeDist,
            Map<String, Integer> poolDRepDelegation,
            int currentEpoch, boolean isBootstrapPhase,
            Map<GovActionType, BigDecimal> drepThresholds,
            Map<GovActionType, BigDecimal> spoThresholds) {

        // No committee vote for NoConfidence
        if (!isBootstrapPhase && !checkDRep(votes, drepDist,
                GovActionType.NO_CONFIDENCE, drepThresholds)) return false;
        if (!checkSPO(votes, poolStakeDist, poolDRepDelegation,
                GovActionType.NO_CONFIDENCE, isBootstrapPhase, spoThresholds)) return false;
        return true;
    }

    private boolean evaluateUpdateCommittee(
            Map<GovernanceStateStore.VoterKey, Integer> votes,
            Map<DRepDistKey, BigInteger> drepDist,
            Map<String, BigInteger> poolStakeDist,
            Map<String, Integer> poolDRepDelegation,
            int currentEpoch, boolean isBootstrapPhase, String committeeState,
            int committeeMaxTermLength, GovActionRecord proposal,
            Map<GovActionType, BigDecimal> drepThresholds,
            Map<GovActionType, BigDecimal> spoThresholds) {

        // No committee vote for UpdateCommittee
        if (!isBootstrapPhase && !checkDRep(votes, drepDist,
                GovActionType.UPDATE_COMMITTEE, drepThresholds)) return false;
        if (!checkSPO(votes, poolStakeDist, poolDRepDelegation,
                GovActionType.UPDATE_COMMITTEE, isBootstrapPhase, spoThresholds)) return false;

        // Term validation: all new members' expiration must be <= currentEpoch + maxTermLength
        if (proposal.govAction() instanceof com.bloxbean.cardano.yaci.core.model.governance.actions.UpdateCommittee uc) {
            if (uc.getNewMembersAndTerms() != null) {
                int maxExpiry = currentEpoch + committeeMaxTermLength;
                for (Integer expiryEpoch : uc.getNewMembersAndTerms().values()) {
                    if (expiryEpoch != null && expiryEpoch > maxExpiry) {
                        return false; // Member term exceeds maximum allowed
                    }
                }
            }
        }
        return true;
    }

    private boolean evaluateNewConstitution(
            Map<GovernanceStateStore.VoterKey, Integer> votes,
            Map<DRepDistKey, BigInteger> drepDist,
            Map<CredentialKey, CommitteeMemberRecord> committeeMembers,
            BigDecimal committeeThreshold, String committeeState,
            int currentEpoch, boolean isBootstrapPhase, int committeeMinSize,
            Map<GovActionType, BigDecimal> drepThresholds) {

        if (!checkCommittee(votes, committeeMembers, committeeThreshold, committeeState,
                currentEpoch, isBootstrapPhase, committeeMinSize)) return false;
        if (!isBootstrapPhase && !checkDRep(votes, drepDist,
                GovActionType.NEW_CONSTITUTION, drepThresholds)) return false;
        return true;
    }

    // ===== Threshold Check Helpers =====

    private boolean checkCommittee(
            Map<GovernanceStateStore.VoterKey, Integer> votes,
            Map<CredentialKey, CommitteeMemberRecord> members,
            BigDecimal threshold, String committeeState,
            int currentEpoch, boolean isBootstrapPhase, int committeeMinSize) {

        if ("NO_CONFIDENCE".equals(committeeState)) return false;

        // Post-bootstrap: check min committee size
        if (!isBootstrapPhase) {
            long activeCount = members.values().stream()
                    .filter(m -> !m.resigned() && m.expiryEpoch() > currentEpoch)
                    .count();
            if (activeCount < committeeMinSize) return false;
        }

        var tally = tallyCalculator.computeCommitteeTally(votes, members, currentEpoch);
        return VoteTallyCalculator.committeeThresholdMet(tally, threshold);
    }

    private boolean checkDRep(
            Map<GovernanceStateStore.VoterKey, Integer> votes,
            Map<DRepDistKey, BigInteger> drepDist,
            GovActionType actionType,
            Map<GovActionType, BigDecimal> thresholds) {

        BigDecimal threshold = thresholds.getOrDefault(actionType, BigDecimal.ONE);
        var tally = tallyCalculator.computeDRepTally(votes, drepDist, actionType);
        return VoteTallyCalculator.drepThresholdMet(tally, threshold);
    }

    private boolean checkSPO(
            Map<GovernanceStateStore.VoterKey, Integer> votes,
            Map<String, BigInteger> poolStakeDist,
            Map<String, Integer> poolDRepDelegation,
            GovActionType actionType, boolean isBootstrapPhase,
            Map<GovActionType, BigDecimal> thresholds) {

        BigDecimal threshold = thresholds.getOrDefault(actionType, BigDecimal.ONE);
        var tally = tallyCalculator.computeSPOTally(votes, poolStakeDist, poolDRepDelegation,
                actionType, isBootstrapPhase);
        return VoteTallyCalculator.spoThresholdMet(tally, threshold);
    }

    // ===== Prev Action Validation =====

    private boolean prevActionValid(GovActionRecord proposal,
                                    Map<GovActionType, GovActionId> lastEnactedActions) {
        GovActionType type = proposal.actionType();

        // Action types without prev action chains
        if (type == GovActionType.TREASURY_WITHDRAWALS_ACTION || type == GovActionType.INFO_ACTION) {
            return true;
        }

        // Map action type to the purpose type for last-enacted lookup
        GovActionType purposeType = switch (type) {
            case NO_CONFIDENCE, UPDATE_COMMITTEE -> GovActionType.UPDATE_COMMITTEE; // shared purpose
            default -> type;
        };

        GovActionId lastEnacted = lastEnactedActions.get(purposeType);
        String prevTxHash = proposal.prevActionTxHash();
        Integer prevIdx = proposal.prevActionIndex();

        if (prevTxHash == null && lastEnacted == null) return true; // Both null = genesis
        if (prevTxHash == null || lastEnacted == null) return false; // One null, other not
        return prevTxHash.equals(lastEnacted.getTransactionId())
                && prevIdx != null && prevIdx.equals(lastEnacted.getGov_action_index());
    }

    // ===== Priority & Delaying =====

    static int getActionPriority(GovActionType type) {
        return switch (type) {
            case NO_CONFIDENCE -> 0;
            case UPDATE_COMMITTEE -> 1;
            case NEW_CONSTITUTION -> 2;
            case HARD_FORK_INITIATION_ACTION -> 3;
            case PARAMETER_CHANGE_ACTION -> 4;
            case TREASURY_WITHDRAWALS_ACTION -> 5;
            case INFO_ACTION -> 6;
        };
    }

    static boolean isDelayingAction(GovActionType type) {
        return switch (type) {
            case NO_CONFIDENCE, UPDATE_COMMITTEE, NEW_CONSTITUTION,
                 HARD_FORK_INITIATION_ACTION, PARAMETER_CHANGE_ACTION -> true;
            default -> false;
        };
    }
}
