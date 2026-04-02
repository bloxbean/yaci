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

import java.util.Set;

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

    // ===== Stateless Evaluation (testable, no store dependency) =====

    /**
     * Evaluate a single proposal using pre-computed tallies. Fully stateless — no DB access.
     * <p>
     * This is the core ratification logic matching the Cardano Conway spec (Amaru reference):
     * <ol>
     *   <li>Lifecycle check (expired / too fresh)</li>
     *   <li>Previous action chain validation</li>
     *   <li>Per-body threshold checks (committee → DRep → SPO) based on action type</li>
     * </ol>
     *
     * @param input             Pre-computed evaluation input (tallies + thresholds)
     * @param currentEpoch      Current epoch at boundary
     * @param isBootstrapPhase  Protocol v9 bootstrap (DRep thresholds = 0)
     * @param lastEnactedActions Last enacted action per type (for prev-action chain)
     * @param committeeState    "NORMAL" or "NO_CONFIDENCE"
     * @param committeeMinSize  Minimum active committee members required
     * @param committeeMaxTermLength Max committee member term
     * @param delayed           Whether a delaying action was already ratified this epoch
     * @return Ratification status
     */
    public static RatificationResult.Status evaluateStateless(
            ProposalEvaluationInput input,
            int currentEpoch,
            boolean isBootstrapPhase,
            Map<GovActionType, GovActionId> lastEnactedActions,
            String committeeState,
            int committeeMinSize,
            int committeeMaxTermLength,
            boolean delayed) {

        GovActionRecord proposal = input.proposal();
        GovActionType type = proposal.actionType();

        // 1. Lifecycle check
        boolean isExpired = (currentEpoch - proposal.expiresAfterEpoch()) > 1;
        boolean isLastChance = (currentEpoch - proposal.expiresAfterEpoch()) == 1;
        if (isExpired) return Status.EXPIRED;
        if (type == GovActionType.INFO_ACTION) return isLastChance ? Status.EXPIRED : Status.ACTIVE;
        if (delayed) return isLastChance ? Status.EXPIRED : Status.ACTIVE;

        // 2. Previous action chain
        if (!prevActionValid(proposal, lastEnactedActions)) {
            return isLastChance ? Status.EXPIRED : Status.ACTIVE;
        }

        // 3. Per-body checks based on action type
        boolean accepted = switch (type) {
            case HARD_FORK_INITIATION_ACTION -> {
                if (!committeeCheck(input, committeeState, isBootstrapPhase, committeeMinSize)) yield false;
                if (!isBootstrapPhase && !drepCheck(input)) yield false;
                if (!spoCheck(input)) yield false;
                yield true;
            }
            case PARAMETER_CHANGE_ACTION -> {
                if (!committeeCheck(input, committeeState, isBootstrapPhase, committeeMinSize)) yield false;
                if (!isBootstrapPhase && !drepCheck(input)) yield false;
                // SPO only if threshold > 0 (security params)
                if (input.spoThreshold().compareTo(BigDecimal.ZERO) > 0 && !spoCheck(input)) yield false;
                yield true;
            }
            case TREASURY_WITHDRAWALS_ACTION -> {
                if (!committeeCheck(input, committeeState, isBootstrapPhase, committeeMinSize)) yield false;
                if (!isBootstrapPhase && !drepCheck(input)) yield false;
                // Treasury balance check
                // TODO: sum all TW proposals' withdrawals vs treasury
                yield true;
            }
            case NO_CONFIDENCE -> {
                // Committee does NOT vote on its own dissolution
                if (!isBootstrapPhase && !drepCheck(input)) yield false;
                if (!spoCheck(input)) yield false;
                yield true;
            }
            case UPDATE_COMMITTEE -> {
                // Committee does NOT vote on its own update
                if (!isBootstrapPhase && !drepCheck(input)) yield false;
                if (!spoCheck(input)) yield false;
                yield true;
            }
            case NEW_CONSTITUTION -> {
                if (!committeeCheck(input, committeeState, isBootstrapPhase, committeeMinSize)) yield false;
                if (!isBootstrapPhase && !drepCheck(input)) yield false;
                yield true;
            }
            case INFO_ACTION -> false; // Already handled above
        };

        if (accepted) return Status.RATIFIED;
        return isLastChance ? Status.EXPIRED : Status.ACTIVE;
    }

    private static boolean committeeCheck(ProposalEvaluationInput input, String committeeState,
                                           boolean isBootstrapPhase, int committeeMinSize) {
        if ("NO_CONFIDENCE".equals(committeeState)) return false;
        var tally = input.committeeTally();
        if (!isBootstrapPhase) {
            int eligible = tally.yesCount() + tally.noCount() + tally.abstainCount();
            if (eligible < committeeMinSize) return false;
        }
        return VoteTallyCalculator.committeeThresholdMet(tally, input.committeeThreshold());
    }

    private static boolean drepCheck(ProposalEvaluationInput input) {
        return VoteTallyCalculator.drepThresholdMet(input.drepTally(), input.drepThreshold());
    }

    private static boolean spoCheck(ProposalEvaluationInput input) {
        return VoteTallyCalculator.spoThresholdMet(input.spoTally(), input.spoThreshold());
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
            Set<DRepDistKey> activeDRepKeys,
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

            Status status = evaluateProposal(id, proposal, drepDist, activeDRepKeys,
                    poolStakeDist, poolDRepDelegation,
                    committeeMembers, committeeThreshold, lastEnactedActions, currentEpoch,
                    isBootstrapPhase, committeeMinSize, committeeMaxTermLength,
                    committeeState, treasury, drepThresholds, spoThresholds, delayed);

            if (currentEpoch == 232) {
                log.info("EVAL@232: {}/{} type={} status={} delayed={}",
                        id.getTransactionId().substring(0, 8), id.getGov_action_index(),
                        proposal.actionType(), status, delayed);
            }
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
            Set<DRepDistKey> activeDRepKeys,
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
            if (id.getTransactionId().startsWith("49578eba")) {
                GovActionType pt = (type == GovActionType.NO_CONFIDENCE || type == GovActionType.UPDATE_COMMITTEE)
                        ? GovActionType.UPDATE_COMMITTEE : type;
                log.info("TRACE-82: prevAction INVALID at epoch {} — lastEnacted[{}]={}, proposal prev={}/{}",
                        currentEpoch, pt, lastEnactedActions.get(pt),
                        proposal.prevActionTxHash(), proposal.prevActionIndex());
            }
            return isLastChance ? Status.EXPIRED : Status.ACTIVE;
        }

        // Get votes for this proposal
        Map<GovernanceStateStore.VoterKey, Integer> votes =
                governanceStore.getVotesForProposal(id.getTransactionId(), id.getGov_action_index());

        // Evaluate per action type
        boolean accepted = switch (type) {
            case HARD_FORK_INITIATION_ACTION -> evaluateHardFork(
                    votes, drepDist, activeDRepKeys, poolStakeDist, poolDRepDelegation,
                    committeeMembers, committeeThreshold, committeeState,
                    currentEpoch, isBootstrapPhase, committeeMinSize,
                    drepThresholds, spoThresholds);

            case PARAMETER_CHANGE_ACTION -> evaluateParameterChange(
                    votes, drepDist, activeDRepKeys, poolStakeDist, poolDRepDelegation,
                    committeeMembers, committeeThreshold, committeeState,
                    currentEpoch, isBootstrapPhase, committeeMinSize,
                    drepThresholds, spoThresholds, proposal);

            case TREASURY_WITHDRAWALS_ACTION -> evaluateTreasuryWithdrawal(
                    votes, drepDist, activeDRepKeys, committeeMembers, committeeThreshold,
                    committeeState, currentEpoch, isBootstrapPhase,
                    committeeMinSize, treasury, proposal, drepThresholds);

            case NO_CONFIDENCE -> evaluateNoConfidence(
                    votes, drepDist, activeDRepKeys, poolStakeDist, poolDRepDelegation,
                    currentEpoch, isBootstrapPhase, drepThresholds, spoThresholds);

            case UPDATE_COMMITTEE -> evaluateUpdateCommittee(
                    votes, drepDist, activeDRepKeys, poolStakeDist, poolDRepDelegation,
                    currentEpoch, isBootstrapPhase, committeeState,
                    committeeMaxTermLength, proposal,
                    drepThresholds, spoThresholds);

            case NEW_CONSTITUTION -> evaluateNewConstitution(
                    votes, drepDist, activeDRepKeys, committeeMembers, committeeThreshold,
                    committeeState, currentEpoch, isBootstrapPhase,
                    committeeMinSize, drepThresholds);

            case INFO_ACTION -> false; // Already handled above
        };

        if (currentEpoch == 232 && id.getTransactionId().startsWith("49578eba")) {
            log.info("TRACE-82-FINAL: accepted={} type={} isLastChance={} votes={}",
                    accepted, type, isLastChance, votes.size());
        }
        if (accepted) return Status.RATIFIED;
        return isLastChance ? Status.EXPIRED : Status.ACTIVE;
    }

    // ===== Per-Action-Type Evaluators =====

    private boolean evaluateHardFork(
            Map<GovernanceStateStore.VoterKey, Integer> votes,
            Map<DRepDistKey, BigInteger> drepDist,
            Set<DRepDistKey> activeDRepKeys,
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
        if (!isBootstrapPhase && !checkDRep(votes, drepDist, activeDRepKeys,
                GovActionType.HARD_FORK_INITIATION_ACTION, drepThresholds)) return false;
        return true;
    }

    private boolean evaluateParameterChange(
            Map<GovernanceStateStore.VoterKey, Integer> votes,
            Map<DRepDistKey, BigInteger> drepDist,
            Set<DRepDistKey> activeDRepKeys,
            Map<String, BigInteger> poolStakeDist,
            Map<String, Integer> poolDRepDelegation,
            Map<CredentialKey, CommitteeMemberRecord> committeeMembers,
            BigDecimal committeeThreshold, String committeeState,
            int currentEpoch, boolean isBootstrapPhase, int committeeMinSize,
            Map<GovActionType, BigDecimal> drepThresholds,
            Map<GovActionType, BigDecimal> spoThresholds,
            GovActionRecord proposal) {

        boolean is82 = proposal.proposalSlot() == 97796216L;

        boolean ccPass = checkCommittee(votes, committeeMembers, committeeThreshold, committeeState,
                currentEpoch, isBootstrapPhase, committeeMinSize);
        if (is82) log.info("P82: cc={} members={} threshold={} state={} minSize={}",
                ccPass, committeeMembers.size(), committeeThreshold, committeeState, committeeMinSize);
        if (!ccPass) return false;

        if (isBootstrapPhase) return true;

        boolean drepPass = checkDRep(votes, drepDist, activeDRepKeys, GovActionType.PARAMETER_CHANGE_ACTION, drepThresholds);
        if (is82) log.info("P82: drep={} threshold={} active={}",
                drepPass, drepThresholds.get(GovActionType.PARAMETER_CHANGE_ACTION),
                activeDRepKeys != null ? activeDRepKeys.size() : "null");
        if (!drepPass) return false;

        boolean spoRequired = false;
        if (proposal.govAction() instanceof com.bloxbean.cardano.yaci.core.model.governance.actions.ParameterChangeAction pca
                && pca.getProtocolParamUpdate() != null) {
            var groups = ProtocolParamGroupClassifier.getAffectedGroups(pca.getProtocolParamUpdate());
            spoRequired = ProtocolParamGroupClassifier.isSpoVotingRequired(groups);
            if (is82) log.info("P82: groups={} spoRequired={}", groups, spoRequired);
        } else if (is82) {
            log.info("P82: govAction={} — no ProtocolParamUpdate!", proposal.govAction());
        }
        if (spoRequired) {
            boolean spoPass = checkSPO(votes, poolStakeDist, poolDRepDelegation,
                    GovActionType.PARAMETER_CHANGE_ACTION, isBootstrapPhase, spoThresholds);
            if (is82) log.info("P82: spo={}", spoPass);
            if (!spoPass) return false;
        }

        if (is82) log.info("P82: ALL PASSED → true");
        return true;
    }

    private boolean evaluateTreasuryWithdrawal(
            Map<GovernanceStateStore.VoterKey, Integer> votes,
            Map<DRepDistKey, BigInteger> drepDist,
            Set<DRepDistKey> activeDRepKeys,
            Map<CredentialKey, CommitteeMemberRecord> committeeMembers,
            BigDecimal committeeThreshold, String committeeState,
            int currentEpoch, boolean isBootstrapPhase, int committeeMinSize,
            BigInteger treasury, GovActionRecord proposal,
            Map<GovActionType, BigDecimal> drepThresholds) {

        if (!checkCommittee(votes, committeeMembers, committeeThreshold, committeeState,
                currentEpoch, isBootstrapPhase, committeeMinSize)) return false;
        if (!isBootstrapPhase && !checkDRep(votes, drepDist, activeDRepKeys,
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
            Set<DRepDistKey> activeDRepKeys,
            Map<String, BigInteger> poolStakeDist,
            Map<String, Integer> poolDRepDelegation,
            int currentEpoch, boolean isBootstrapPhase,
            Map<GovActionType, BigDecimal> drepThresholds,
            Map<GovActionType, BigDecimal> spoThresholds) {

        // No committee vote for NoConfidence
        if (!isBootstrapPhase && !checkDRep(votes, drepDist, activeDRepKeys,
                GovActionType.NO_CONFIDENCE, drepThresholds)) return false;
        if (!checkSPO(votes, poolStakeDist, poolDRepDelegation,
                GovActionType.NO_CONFIDENCE, isBootstrapPhase, spoThresholds)) return false;
        return true;
    }

    private boolean evaluateUpdateCommittee(
            Map<GovernanceStateStore.VoterKey, Integer> votes,
            Map<DRepDistKey, BigInteger> drepDist,
            Set<DRepDistKey> activeDRepKeys,
            Map<String, BigInteger> poolStakeDist,
            Map<String, Integer> poolDRepDelegation,
            int currentEpoch, boolean isBootstrapPhase, String committeeState,
            int committeeMaxTermLength, GovActionRecord proposal,
            Map<GovActionType, BigDecimal> drepThresholds,
            Map<GovActionType, BigDecimal> spoThresholds) {

        // No committee vote for UpdateCommittee
        if (!isBootstrapPhase && !checkDRep(votes, drepDist, activeDRepKeys,
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
            Set<DRepDistKey> activeDRepKeys,
            Map<CredentialKey, CommitteeMemberRecord> committeeMembers,
            BigDecimal committeeThreshold, String committeeState,
            int currentEpoch, boolean isBootstrapPhase, int committeeMinSize,
            Map<GovActionType, BigDecimal> drepThresholds) {

        if (!checkCommittee(votes, committeeMembers, committeeThreshold, committeeState,
                currentEpoch, isBootstrapPhase, committeeMinSize)) return false;
        if (!isBootstrapPhase && !checkDRep(votes, drepDist, activeDRepKeys,
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
            Set<DRepDistKey> activeDRepKeys,
            GovActionType actionType,
            Map<GovActionType, BigDecimal> thresholds) {

        BigDecimal threshold = thresholds.getOrDefault(actionType, BigDecimal.ONE);
        var tally = tallyCalculator.computeDRepTally(votes, drepDist, actionType, activeDRepKeys);
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

    static boolean prevActionValid(GovActionRecord proposal,
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
