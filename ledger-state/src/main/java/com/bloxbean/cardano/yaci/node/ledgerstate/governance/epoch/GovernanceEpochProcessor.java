package com.bloxbean.cardano.yaci.node.ledgerstate.governance.epoch;

import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import com.bloxbean.cardano.yaci.node.api.EpochParamProvider;
import com.bloxbean.cardano.yaci.node.ledgerstate.AccountStateCborCodec;
import com.bloxbean.cardano.yaci.node.ledgerstate.AdaPotTracker;
import com.bloxbean.cardano.yaci.node.ledgerstate.DefaultAccountStateStore.DeltaOp;
import com.bloxbean.cardano.yaci.node.ledgerstate.EpochParamTracker;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.GovernanceStateStore;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.GovernanceStateStore.CredentialKey;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.epoch.DRepDistributionCalculator.DRepDistKey;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.CommitteeMemberRecord;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.DRepStateRecord;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.GovActionRecord;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.RatificationResult;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.ratification.EnactmentProcessor;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.ratification.ProposalDropService;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.ratification.RatificationEngine;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Orchestrates all governance processing at epoch boundaries.
 * Called from EpochBoundaryProcessor.processEpochBoundary() BEFORE reward calculation.
 * <p>
 * Processing order (must match Haskell ledger):
 * <ol>
 *   <li>Calculate DRep distribution</li>
 *   <li>RATIFY: Evaluate all active proposals against vote thresholds</li>
 *   <li>ENACT: Apply ratified actions</li>
 *   <li>Remove expired + conflicting proposals, refund deposits</li>
 *   <li>Update DRep expiry</li>
 *   <li>Update dormant epoch tracking</li>
 *   <li>Process donations</li>
 * </ol>
 */
public class GovernanceEpochProcessor {
    private static final Logger log = LoggerFactory.getLogger(GovernanceEpochProcessor.class);

    private final RocksDB db;
    private final ColumnFamilyHandle cfState;
    private final ColumnFamilyHandle cfDelta;
    private final GovernanceStateStore governanceStore;
    private final DRepDistributionCalculator drepDistCalculator;
    private final DRepExpiryCalculator drepExpiryCalculator;
    private final RatificationEngine ratificationEngine;
    private final EnactmentProcessor enactmentProcessor;
    private final ProposalDropService proposalDropService;
    private final EpochParamProvider paramProvider;
    private final EpochParamTracker paramTracker;
    private final AdaPotTracker adaPotTracker;
    private final PoolStakeResolver poolStakeResolver;
    private final RewardRestStore rewardRestStore;

    // Conway era first epoch (network-specific, set during bootstrap)
    private int conwayFirstEpoch = -1;
    private volatile boolean genesisBootstrapped = false;
    private final com.bloxbean.cardano.yaci.node.ledgerstate.governance.ConwayGenesisBootstrap genesisBootstrap;

    /**
     * Resolves pool stake distribution and pool-to-DRep delegation mapping for SPO voting.
     * Implemented by the caller (DefaultAccountStateStore) which has access to epoch snapshots.
     */
    public interface PoolStakeResolver {
        PoolStakeData resolvePoolStake(int epoch) throws RocksDBException;
    }

    /** Pool stake distribution and DRep delegation data for SPO voting. */
    public record PoolStakeData(
            Map<String, BigInteger> poolStakes,
            Map<String, Integer> poolDRepDelegations
    ) {
        public static final PoolStakeData EMPTY = new PoolStakeData(Map.of(), Map.of());
    }

    /**
     * Stores a deferred reward (reward_rest) that becomes spendable and counts toward
     * stake at a future epoch. Used for proposal deposit refunds and treasury withdrawals.
     */
    public interface RewardRestStore {
        /**
         * Store a reward_rest entry.
         *
         * @param spendableEpoch   Epoch when this reward becomes part of the stake snapshot
         * @param type             Reward type byte (REWARD_REST_PROPOSAL_REFUND, etc.)
         * @param rewardAccountHex Reward account in hex (header + credential hash)
         * @param amount           Amount in lovelace
         * @param earnedEpoch      Epoch when the reward was earned
         * @param slot             Slot of the triggering event
         * @param batch            WriteBatch for atomic writes
         * @param deltaOps         Delta ops for rollback
         * @return true if stored (valid address), false if invalid
         */
        boolean storeRewardRest(int spendableEpoch, byte type, String rewardAccountHex,
                                BigInteger amount, int earnedEpoch, long slot,
                                WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException;
    }

    public GovernanceEpochProcessor(RocksDB db, ColumnFamilyHandle cfState, ColumnFamilyHandle cfDelta,
                                    GovernanceStateStore governanceStore,
                                    DRepDistributionCalculator drepDistCalculator,
                                    DRepExpiryCalculator drepExpiryCalculator,
                                    RatificationEngine ratificationEngine,
                                    EnactmentProcessor enactmentProcessor,
                                    ProposalDropService proposalDropService,
                                    EpochParamProvider paramProvider,
                                    EpochParamTracker paramTracker,
                                    AdaPotTracker adaPotTracker,
                                    PoolStakeResolver poolStakeResolver,
                                    RewardRestStore rewardRestStore,
                                    String conwayGenesisFilePath) {
        this.db = db;
        this.cfState = cfState;
        this.cfDelta = cfDelta;
        this.governanceStore = governanceStore;
        this.drepDistCalculator = drepDistCalculator;
        this.drepExpiryCalculator = drepExpiryCalculator;
        this.ratificationEngine = ratificationEngine;
        this.enactmentProcessor = enactmentProcessor;
        this.proposalDropService = proposalDropService;
        this.paramProvider = paramProvider;
        this.paramTracker = paramTracker;
        this.adaPotTracker = adaPotTracker;
        this.poolStakeResolver = poolStakeResolver;
        this.rewardRestStore = rewardRestStore;
        this.genesisBootstrap = new com.bloxbean.cardano.yaci.node.ledgerstate.governance.ConwayGenesisBootstrap(
                governanceStore, conwayGenesisFilePath);
    }

    public void setConwayFirstEpoch(int epoch) {
        this.conwayFirstEpoch = epoch;
    }

    /**
     * Process governance epoch boundary and commit all writes atomically.
     * Called from EpochBoundaryProcessor which doesn't manage a WriteBatch.
     */
    public GovernanceEpochResult processEpochBoundaryAndCommit(int previousEpoch, int newEpoch)
            throws RocksDBException {
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            List<DeltaOp> deltaOps = new ArrayList<>();
            GovernanceEpochResult result = processEpochBoundary(previousEpoch, newEpoch, batch, deltaOps);

            // Write delta log for rollback support (keyed by a governance-specific marker)
            // Governance epoch deltas use a special key to distinguish from block-level deltas
            // For now, governance epoch changes are committed without delta logging
            // since epoch boundary processing is idempotent (re-run produces same result).
            // TODO: Add delta logging for full rollback support of epoch governance state

            db.write(wo, batch);
            return result;
        }
    }

    /**
     * Process governance state at epoch boundary.
     *
     * @param previousEpoch The epoch that just ended
     * @param newEpoch      The epoch starting
     * @param batch         WriteBatch for atomic writes
     * @param deltaOps      Delta ops for rollback
     * @return Result containing treasury/deposit deltas for AdaPot
     */
    public GovernanceEpochResult processEpochBoundary(int previousEpoch, int newEpoch,
                                                      WriteBatch batch, List<DeltaOp> deltaOps)
            throws RocksDBException {
        long start = System.currentTimeMillis();
        int protocolVersion = resolveProtocolMajor(newEpoch);

        // Skip if not in Conway era
        if (protocolVersion < 9) {
            return GovernanceEpochResult.EMPTY;
        }

        // Bootstrap Conway genesis on first Conway epoch (committee, constitution, params)
        if (!genesisBootstrapped) {
            if (conwayFirstEpoch < 0) {
                conwayFirstEpoch = newEpoch;
            }
            genesisBootstrap.bootstrap(conwayFirstEpoch, batch, deltaOps);
            genesisBootstrapped = true;
        }

        boolean isBootstrapPhase = protocolVersion < 10;

        log.info("Governance epoch boundary {} → {} (protocolVersion={}, bootstrap={})",
                previousEpoch, newEpoch, protocolVersion, isBootstrapPhase);

        // 1. Calculate DRep distribution (using snapshot from previousEpoch)
        int maxBootstrapEpoch = isBootstrapPhase ? -1 : findMaxBootstrapEpoch();
        Map<DRepDistKey, BigInteger> drepDist =
                drepDistCalculator.calculate(previousEpoch, protocolVersion, maxBootstrapEpoch);

        // Store DRep distribution snapshot (skip virtual DReps — they have synthetic non-hex hashes)
        for (var entry : drepDist.entrySet()) {
            DRepDistKey dk = entry.getKey();
            if (dk.drepType() <= 1) { // Only regular DReps (0=key, 1=script), not virtual (2=abstain, 3=no_confidence)
                governanceStore.storeDRepDistEntry(newEpoch, dk.drepType(),
                        dk.drepHash(), entry.getValue(), batch);
            }
        }

        // 2. RATIFY: evaluate all active proposals
        Map<GovActionId, GovActionRecord> activeProposals = governanceStore.getAllActiveProposals();
        Map<CredentialKey, CommitteeMemberRecord> committeeMembers = governanceStore.getAllCommitteeMembers();

        // Resolve thresholds and committee state
        BigDecimal committeeThreshold = resolveCommitteeThreshold();
        String committeeState = resolveCommitteeState(committeeMembers, newEpoch);
        Map<GovActionType, GovActionId> lastEnactedActions = resolveLastEnactedActions();
        Map<GovActionType, BigDecimal> drepThresholds = resolveDRepThresholds(isBootstrapPhase);
        Map<GovActionType, BigDecimal> spoThresholds = resolveSPOThresholds();
        int committeeMinSize = paramProvider.getCommitteeMinSize(newEpoch);
        int committeeMaxTermLength = paramProvider.getCommitteeMaxTermLength(newEpoch);

        // Resolve pool stake distribution and pool DRep delegations for SPO voting
        PoolStakeData poolData = PoolStakeData.EMPTY;
        if (poolStakeResolver != null) {
            poolData = poolStakeResolver.resolvePoolStake(previousEpoch);
            if (poolData == null) poolData = PoolStakeData.EMPTY;
        }
        Map<String, BigInteger> poolStakeDist = poolData.poolStakes();
        Map<String, Integer> poolDRepDelegation = poolData.poolDRepDelegations();

        // Resolve treasury from AdaPot
        BigInteger treasury = BigInteger.ZERO;
        if (adaPotTracker != null && adaPotTracker.isEnabled()) {
            var prevPot = adaPotTracker.getAdaPot(previousEpoch);
            if (prevPot.isPresent()) {
                treasury = prevPot.get().treasury();
            }
        }

        List<RatificationResult> results = ratificationEngine.evaluateAll(
                activeProposals, drepDist, poolStakeDist, poolDRepDelegation,
                committeeMembers, committeeThreshold, lastEnactedActions,
                newEpoch, isBootstrapPhase, committeeMinSize, committeeMaxTermLength,
                committeeState, treasury, drepThresholds, spoThresholds);

        // 3. ENACT ratified actions
        BigInteger treasuryDelta = BigInteger.ZERO;
        for (RatificationResult result : results) {
            if (result.isRatified()) {
                BigInteger delta = enactmentProcessor.enact(result.govActionId(), result.proposal(),
                        newEpoch, batch, deltaOps);
                treasuryDelta = treasuryDelta.add(delta);
            }
        }

        // 3.5 Store treasury withdrawal amounts as reward_rest (spendable at newEpoch + 1)
        if (rewardRestStore != null) {
            for (RatificationResult result : results) {
                if (result.isRatified() && result.proposal().govAction()
                        instanceof com.bloxbean.cardano.yaci.core.model.governance.actions.TreasuryWithdrawalsAction twa) {
                    if (twa.getWithdrawals() != null) {
                        for (var entry : twa.getWithdrawals().entrySet()) {
                            boolean stored = rewardRestStore.storeRewardRest(
                                    newEpoch + 1,
                                    com.bloxbean.cardano.yaci.node.ledgerstate.DefaultAccountStateStore.REWARD_REST_TREASURY_WITHDRAWAL,
                                    entry.getKey(), entry.getValue(), newEpoch, 0,
                                    batch, deltaOps);
                            if (!stored) {
                                treasuryDelta = treasuryDelta.add(entry.getValue());
                                log.info("Treasury withdrawal to {} unclaimed, returned to treasury",
                                        entry.getKey().substring(0, Math.min(16, entry.getKey().length())));
                            }
                        }
                    }
                }
            }
        }

        // 4. Remove expired + conflicting proposals, compute deposit refunds
        Set<GovActionId> proposalsToDrop = proposalDropService.computeProposalsToDrop(results, activeProposals);
        BigInteger depositRefunds = BigInteger.ZERO;
        BigInteger unclaimedRefunds = BigInteger.ZERO;

        // Remove ratified and expired proposals — store deposit refunds as reward_rest
        for (RatificationResult result : results) {
            if (result.isRatified() || result.isExpired()) {
                BigInteger deposit = result.proposal().deposit();
                depositRefunds = depositRefunds.add(deposit);

                // Store deposit refund as reward_rest (spendable at newEpoch + 1)
                if (rewardRestStore != null && deposit.signum() > 0) {
                    boolean stored = rewardRestStore.storeRewardRest(
                            newEpoch + 1,
                            com.bloxbean.cardano.yaci.node.ledgerstate.DefaultAccountStateStore.REWARD_REST_PROPOSAL_REFUND,
                            result.proposal().returnAddress(), deposit, newEpoch, 0,
                            batch, deltaOps);
                    if (!stored) {
                        unclaimedRefunds = unclaimedRefunds.add(deposit);
                    }
                }

                governanceStore.removeProposal(result.govActionId(), batch, deltaOps);
                governanceStore.removeVotesForProposal(result.govActionId().getTransactionId(),
                        result.govActionId().getGov_action_index(), batch, deltaOps);
            }
        }

        // Remove dropped proposals (siblings/descendants) — store deposit refunds as reward_rest
        for (GovActionId dropId : proposalsToDrop) {
            GovActionRecord dropped = activeProposals.get(dropId);
            if (dropped != null) {
                BigInteger deposit = dropped.deposit();
                depositRefunds = depositRefunds.add(deposit);

                // Store deposit refund as reward_rest (spendable at newEpoch + 1)
                if (rewardRestStore != null && deposit.signum() > 0) {
                    boolean stored = rewardRestStore.storeRewardRest(
                            newEpoch + 1,
                            com.bloxbean.cardano.yaci.node.ledgerstate.DefaultAccountStateStore.REWARD_REST_PROPOSAL_REFUND,
                            dropped.returnAddress(), deposit, newEpoch, 0,
                            batch, deltaOps);
                    if (!stored) {
                        unclaimedRefunds = unclaimedRefunds.add(deposit);
                    }
                }

                governanceStore.removeProposal(dropId, batch, deltaOps);
                governanceStore.removeVotesForProposal(dropId.getTransactionId(),
                        dropId.getGov_action_index(), batch, deltaOps);
            }
        }

        // Unclaimed refunds go to treasury
        if (unclaimedRefunds.signum() > 0) {
            treasuryDelta = treasuryDelta.add(unclaimedRefunds);
            log.info("Unclaimed proposal deposit refunds going to treasury: {}", unclaimedRefunds);
        }

        // Note: DRep deposit refunds are handled immediately on UnregDrepCert processing
        // in DefaultAccountStateStore.processCertificate(), not at epoch boundary.
        // Only PROPOSAL deposits are refunded at epoch boundary (when proposals expire/enacted/dropped).

        // 5. Update DRep expiry
        updateDRepExpiry(newEpoch, batch, deltaOps);

        // 6. Update dormant epoch tracking
        // After removing expired/ratified/dropped proposals, check if any remain active
        Map<GovActionId, GovActionRecord> remainingProposals = governanceStore.getAllActiveProposals();
        boolean epochHadActiveProposals = !remainingProposals.isEmpty();
        governanceStore.storeEpochHadActiveProposals(newEpoch, epochHadActiveProposals, batch, deltaOps);

        Set<Integer> dormantEpochs = governanceStore.getDormantEpochs();
        if (!epochHadActiveProposals) {
            dormantEpochs.add(newEpoch);
        }
        // First Conway epoch is always dormant
        if (conwayFirstEpoch >= 0 && newEpoch == conwayFirstEpoch && !dormantEpochs.contains(newEpoch)) {
            dormantEpochs.add(newEpoch);
        }
        governanceStore.storeDormantEpochs(dormantEpochs, batch, deltaOps);

        // 7. Process epoch donations
        BigInteger donations = governanceStore.getEpochDonations(previousEpoch);

        long elapsed = System.currentTimeMillis() - start;
        log.info("Governance epoch boundary complete ({} → {}) in {}ms: {} ratified, {} expired, " +
                        "{} dropped, depositRefunds={}, donations={}, dormant={}",
                previousEpoch, newEpoch, elapsed,
                results.stream().filter(RatificationResult::isRatified).count(),
                results.stream().filter(RatificationResult::isExpired).count(),
                proposalsToDrop.size(), depositRefunds, donations, !epochHadActiveProposals);

        return new GovernanceEpochResult(treasuryDelta, depositRefunds, donations);
    }

    // ===== DRep Expiry Update =====

    private void updateDRepExpiry(int newEpoch, WriteBatch batch, List<DeltaOp> deltaOps)
            throws RocksDBException {
        Set<Integer> dormantEpochs = governanceStore.getDormantEpochs();
        int drepActivity = paramProvider.getDRepActivity(newEpoch);
        int eraFirstEpoch = conwayFirstEpoch >= 0 ? conwayFirstEpoch : newEpoch;

        Map<CredentialKey, DRepStateRecord> allDReps = governanceStore.getActiveDRepStates();
        for (var entry : allDReps.entrySet()) {
            CredentialKey ck = entry.getKey();
            DRepStateRecord state = entry.getValue();

            // TODO: For full v9 bonus accuracy, need to look up latestProposalUpToRegistration
            // from the proposal history. For now, pass null (no v9 bonus).
            int expiry = drepExpiryCalculator.calculateExpiry(state, dormantEpochs, drepActivity,
                    eraFirstEpoch, newEpoch, null, paramProvider.getGovActionLifetime(newEpoch));
            boolean active = expiry >= newEpoch;

            if (expiry != state.expiryEpoch() || active != state.active()) {
                DRepStateRecord updated = state.withExpiry(expiry, active);
                governanceStore.storeDRepState(ck.credType(), ck.hash(), updated, batch, deltaOps);
            }
        }
    }

    // ===== Resolution Helpers =====

    private int resolveProtocolMajor(int epoch) {
        if (paramTracker != null && paramTracker.isEnabled()) {
            return paramTracker.getProtocolMajor(epoch);
        }
        return paramProvider.getProtocolMajor(epoch);
    }

    private BigDecimal resolveCommitteeThreshold() throws RocksDBException {
        var threshold = governanceStore.getCommitteeThreshold();
        if (threshold.isPresent()) {
            var t = threshold.get();
            if (t.denominator().signum() > 0) {
                return new BigDecimal(t.numerator()).divide(
                        new BigDecimal(t.denominator()), java.math.MathContext.DECIMAL128);
            }
        }
        return new BigDecimal("0.667"); // default 2/3
    }

    private String resolveCommitteeState(Map<CredentialKey, CommitteeMemberRecord> members,
                                         int epoch) {
        // If no members exist at all, treat as NO_CONFIDENCE
        if (members.isEmpty()) return "NO_CONFIDENCE";
        // Check if any non-expired, non-resigned members exist
        boolean hasActive = members.values().stream()
                .anyMatch(m -> !m.resigned() && m.expiryEpoch() > epoch);
        return hasActive ? "NORMAL" : "NO_CONFIDENCE";
    }

    private Map<GovActionType, GovActionId> resolveLastEnactedActions() throws RocksDBException {
        Map<GovActionType, GovActionId> result = new HashMap<>();
        for (GovActionType type : GovActionType.values()) {
            var last = governanceStore.getLastEnactedAction(type);
            if (last.isPresent()) {
                result.put(type, new GovActionId(last.get().txHash(), last.get().govActionIndex()));
            }
        }
        return result;
    }

    private Map<GovActionType, BigDecimal> resolveDRepThresholds(boolean isBootstrapPhase) {
        Map<GovActionType, BigDecimal> thresholds = new HashMap<>();
        if (isBootstrapPhase) {
            // Bootstrap: all DRep thresholds = 0 (auto-approve)
            for (GovActionType type : GovActionType.values()) {
                thresholds.put(type, BigDecimal.ZERO);
            }
        } else {
            // TODO: Read actual DRep voting thresholds from protocol params
            // For now, use reasonable defaults
            thresholds.put(GovActionType.NO_CONFIDENCE, new BigDecimal("0.67"));
            thresholds.put(GovActionType.UPDATE_COMMITTEE, new BigDecimal("0.67"));
            thresholds.put(GovActionType.NEW_CONSTITUTION, new BigDecimal("0.75"));
            thresholds.put(GovActionType.HARD_FORK_INITIATION_ACTION, new BigDecimal("0.60"));
            thresholds.put(GovActionType.PARAMETER_CHANGE_ACTION, new BigDecimal("0.67"));
            thresholds.put(GovActionType.TREASURY_WITHDRAWALS_ACTION, new BigDecimal("0.67"));
        }
        return thresholds;
    }

    private Map<GovActionType, BigDecimal> resolveSPOThresholds() {
        // TODO: Read actual SPO voting thresholds from protocol params
        Map<GovActionType, BigDecimal> thresholds = new HashMap<>();
        thresholds.put(GovActionType.NO_CONFIDENCE, new BigDecimal("0.60"));
        thresholds.put(GovActionType.UPDATE_COMMITTEE, new BigDecimal("0.51"));
        thresholds.put(GovActionType.HARD_FORK_INITIATION_ACTION, new BigDecimal("0.51"));
        thresholds.put(GovActionType.PARAMETER_CHANGE_ACTION, new BigDecimal("0.51")); // security group
        return thresholds;
    }

    private int findMaxBootstrapEpoch() {
        // TODO: Find the epoch of the last ratified HardForkInitiation that transitioned to v10
        return -1;
    }

    // ===== Result =====

    /**
     * Result of governance epoch boundary processing.
     *
     * @param treasuryDelta  Net change to treasury (negative = withdrawals, positive = donations)
     * @param depositRefunds Total proposal deposits refunded
     * @param donations      Total donations in the previous epoch
     */
    public record GovernanceEpochResult(BigInteger treasuryDelta, BigInteger depositRefunds,
                                        BigInteger donations) {
        public static final GovernanceEpochResult EMPTY =
                new GovernanceEpochResult(BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO);
    }
}
