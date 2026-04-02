package com.bloxbean.cardano.yaci.node.ledgerstate.governance.epoch;

import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import com.bloxbean.cardano.yaci.node.api.EpochParamProvider;
import com.bloxbean.cardano.yaci.node.ledgerstate.AccountStateCborCodec;
import com.bloxbean.cardano.yaci.node.ledgerstate.AdaPotTracker;
import com.bloxbean.cardano.yaci.node.ledgerstate.DefaultAccountStateStore.DeltaOp;
import com.bloxbean.cardano.yaci.core.model.ProtocolParamUpdate;
import com.bloxbean.cardano.yaci.node.ledgerstate.EpochParamTracker;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.ratification.ProtocolParamGroupClassifier;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.GovernanceStateStore;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.GovernanceStateStore.CredentialKey;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.epoch.DRepDistributionCalculator.DRepDistKey;
import java.util.Set;
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

    // Optional epoch snapshot exporter for debugging (NOOP when disabled)
    private com.bloxbean.cardano.yaci.node.ledgerstate.export.EpochSnapshotExporter snapshotExporter =
            com.bloxbean.cardano.yaci.node.ledgerstate.export.EpochSnapshotExporter.NOOP;

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

    public void setSnapshotExporter(com.bloxbean.cardano.yaci.node.ledgerstate.export.EpochSnapshotExporter exporter) {
        this.snapshotExporter = exporter != null ? exporter
                : com.bloxbean.cardano.yaci.node.ledgerstate.export.EpochSnapshotExporter.NOOP;
    }

    /**
     * Process governance epoch boundary in two phases with a commit between.
     * Phase 1 (Enact): applies previously ratified proposals → commits so committee/params
     *                   changes are visible to subsequent reads.
     * Phase 2 (Ratify + rest): evaluates current proposals, stores new pending, updates DReps.
     * Called from EpochBoundaryProcessor.
     */
    public GovernanceEpochResult processEpochBoundaryAndCommit(int previousEpoch, int newEpoch,
            Map<com.bloxbean.cardano.yaci.node.ledgerstate.UtxoBalanceAggregator.CredentialKey,
                    BigInteger> utxoBalances,
            Map<String, BigInteger> spendableRewardRest) throws RocksDBException {

        int protocolVersion = resolveProtocolMajor(newEpoch);
        if (protocolVersion < 9) return GovernanceEpochResult.EMPTY;

        // Phase 1: Bootstrap + Enact pending proposals (writes committee, params, lastEnacted)
        BigInteger enactmentTreasuryDelta;
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            List<DeltaOp> deltaOps = new ArrayList<>();
            enactmentTreasuryDelta = processEnactmentPhase(previousEpoch, newEpoch, batch, deltaOps);
            db.write(wo, batch); // Commit so ratification can see enactment changes
        }

        // Phase 2: Ratification + deposit refunds + dormant + expiry + donations
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            List<DeltaOp> deltaOps = new ArrayList<>();
            GovernanceEpochResult result = processRatificationPhase(previousEpoch, newEpoch,
                    enactmentTreasuryDelta, batch, deltaOps, utxoBalances, spendableRewardRest);
            db.write(wo, batch);
            return result;
        }
    }

    /**
     * Phase 1: Bootstrap Conway genesis + enact previously ratified proposals.
     * Writes committee members, protocol params, and lastEnactedActions to RocksDB.
     * Must be committed before Phase 2 so ratification reads current state.
     *
     * @return treasury delta from enactment (e.g., negative for treasury withdrawals)
     */
    private BigInteger processEnactmentPhase(int previousEpoch, int newEpoch,
                                              WriteBatch batch, List<DeltaOp> deltaOps)
            throws RocksDBException {

        // Bootstrap Conway genesis on first Conway epoch (committee, constitution, params)
        if (!genesisBootstrapped) {
            if (conwayFirstEpoch < 0) {
                conwayFirstEpoch = newEpoch;
            }
            genesisBootstrap.bootstrap(conwayFirstEpoch, batch, deltaOps);
            genesisBootstrapped = true;
        }

        // Enact pending proposals (ratified at previous epoch boundary)
        BigInteger treasuryDelta = BigInteger.ZERO;
        List<GovActionId> pendingEnactmentIds = governanceStore.getPendingEnactments();
        Map<GovActionId, GovActionRecord> allProposals = governanceStore.getAllActiveProposals();

        if (!pendingEnactmentIds.isEmpty()) {
            log.info("Phase 1 enact: {} pending enactments at {} → {}",
                    pendingEnactmentIds.size(), previousEpoch, newEpoch);
        }

        for (GovActionId id : pendingEnactmentIds) {
            GovActionRecord proposal = allProposals.get(id);
            if (proposal != null) {
                BigInteger delta = enactmentProcessor.enact(id, proposal, newEpoch, batch, deltaOps);
                treasuryDelta = treasuryDelta.add(delta);
                log.info("Phase 1 enacted: {}/{} type={}", id.getTransactionId().substring(0, 8),
                        id.getGov_action_index(), proposal.actionType());
            }
        }

        return treasuryDelta;
    }

    /**
     * Phase 2: Ratification + deposit refunds + dormant tracking + DRep expiry + donations.
     * Reads committed state (including Phase 1 enactment writes) for committee/params/lastEnacted.
     */
    private GovernanceEpochResult processRatificationPhase(int previousEpoch, int newEpoch,
                                                            BigInteger enactmentTreasuryDelta,
                                                            WriteBatch batch, List<DeltaOp> deltaOps,
                                                            Map<com.bloxbean.cardano.yaci.node.ledgerstate.UtxoBalanceAggregator.CredentialKey,
                                                                    BigInteger> utxoBalances,
                                                            Map<String, BigInteger> spendableRewardRest)
            throws RocksDBException {
        long start = System.currentTimeMillis();
        int protocolVersion = resolveProtocolMajor(newEpoch);
        boolean isBootstrapPhase = protocolVersion < 10;

        log.info("Governance epoch boundary {} → {} (protocolVersion={}, bootstrap={})",
                previousEpoch, newEpoch, protocolVersion, isBootstrapPhase);

        BigInteger treasuryDelta = enactmentTreasuryDelta;

        // 1. Calculate DRep distribution (using UTXO balances + reward_rest from snapshot step)
        Map<DRepDistKey, BigInteger> drepDist =
                drepDistCalculator.calculate(previousEpoch, utxoBalances, spendableRewardRest);

        // Store DRep distribution snapshot (skip virtual DReps — they have synthetic non-hex hashes)
        for (var entry : drepDist.entrySet()) {
            DRepDistKey dk = entry.getKey();
            if (dk.drepType() <= 1) { // Only regular DReps (0=key, 1=script), not virtual (2=abstain, 3=no_confidence)
                governanceStore.storeDRepDistEntry(newEpoch, dk.drepType(),
                        dk.drepHash(), entry.getValue(), batch);
            }
        }

        // Export DRep distribution snapshot for debugging
        if (snapshotExporter != com.bloxbean.cardano.yaci.node.ledgerstate.export.EpochSnapshotExporter.NOOP) {
            var exportEntries = drepDist.entrySet().stream()
                    .filter(e -> e.getKey().drepType() <= 1) // skip virtual DReps
                    .map(e -> new com.bloxbean.cardano.yaci.node.ledgerstate.export.EpochSnapshotExporter.DRepDistEntry(
                            e.getKey().drepType(), e.getKey().drepHash(), e.getValue()))
                    .toList();
            snapshotExporter.exportDRepDistribution(newEpoch, exportEntries);
        }

        // Build set of ACTIVE (non-expired) DRep keys for ratification tally.
        // Only include DReps that are IN the distribution (have delegated stake).
        Set<DRepDistKey> activeDRepKeys = buildActiveDRepKeys(drepDist, newEpoch);

        // 2. Get active proposals, excluding those pending enactment/drop (already ratified/expired)
        Map<GovActionId, GovActionRecord> activeProposals = governanceStore.getAllActiveProposals();
        List<GovActionId> pendingEnactmentIds = governanceStore.getPendingEnactments();
        List<GovActionId> pendingDropIds = governanceStore.getPendingDrops();
        for (GovActionId id : pendingEnactmentIds) activeProposals.remove(id);
        for (GovActionId id : pendingDropIds) activeProposals.remove(id);

        if (!pendingEnactmentIds.isEmpty() || !pendingDropIds.isEmpty()) {
            log.info("Phase 2: {} pending enactments, {} pending drops at {} → {}",
                    pendingEnactmentIds.size(), pendingDropIds.size(), previousEpoch, newEpoch);
        }

        // 3. Ratify — reads committed state (committee/params/lastEnacted updated by Phase 1).
        Map<CredentialKey, CommitteeMemberRecord> committeeMembers = governanceStore.getAllCommitteeMembers();
        BigDecimal committeeThreshold = resolveCommitteeThreshold();
        String committeeState = resolveCommitteeState(committeeMembers, newEpoch);
        Map<GovActionType, GovActionId> lastEnactedActions = resolveLastEnactedActions();
        Map<GovActionType, BigDecimal> drepThresholds = resolveDRepThresholds(isBootstrapPhase, newEpoch);
        Map<GovActionType, BigDecimal> spoThresholds = resolveSPOThresholds(newEpoch);
        int committeeMinSize = paramProvider.getCommitteeMinSize(newEpoch);
        int committeeMaxTermLength = paramProvider.getCommitteeMaxTermLength(newEpoch);

        PoolStakeData poolData = PoolStakeData.EMPTY;
        if (poolStakeResolver != null) {
            poolData = poolStakeResolver.resolvePoolStake(previousEpoch);
            if (poolData == null) poolData = PoolStakeData.EMPTY;
        }
        Map<String, BigInteger> poolStakeDist = poolData.poolStakes();
        Map<String, Integer> poolDRepDelegation = poolData.poolDRepDelegations();

        BigInteger treasury = BigInteger.ZERO;
        if (adaPotTracker != null && adaPotTracker.isEnabled()) {
            var prevPot = adaPotTracker.getAdaPot(previousEpoch);
            if (prevPot.isPresent()) {
                treasury = prevPot.get().treasury();
            }
        }

        List<RatificationResult> results = ratificationEngine.evaluateAll(
                activeProposals, drepDist, activeDRepKeys, poolStakeDist, poolDRepDelegation,
                committeeMembers, committeeThreshold, lastEnactedActions,
                newEpoch, isBootstrapPhase, committeeMinSize, committeeMaxTermLength,
                committeeState, treasury, drepThresholds, spoThresholds);

        // Store NEW ratified proposals as pending for next boundary
        for (RatificationResult result : results) {
            if (result.isRatified()) {
                governanceStore.storePendingEnactment(result.govActionId(), batch, deltaOps);
                log.info("Phase 2: ratified → pending enactment {}/{} at {} → {}",
                        result.govActionId().getTransactionId().substring(0, 8),
                        result.govActionId().getGov_action_index(), previousEpoch, newEpoch);
            }
        }

        // Export proposal status for debugging
        if (snapshotExporter != com.bloxbean.cardano.yaci.node.ledgerstate.export.EpochSnapshotExporter.NOOP) {
            var statusEntries = results.stream()
                    .map(r -> new com.bloxbean.cardano.yaci.node.ledgerstate.export.EpochSnapshotExporter.ProposalStatusEntry(
                            r.govActionId().getTransactionId(), r.govActionId().getGov_action_index(),
                            r.proposal().actionType().name(), r.status().name(),
                            r.proposal().deposit(), r.proposal().returnAddress(),
                            r.proposal().proposedInEpoch(), r.proposal().expiresAfterEpoch()))
                    .toList();
            snapshotExporter.exportProposalStatus(newEpoch, statusEntries);
        }

        // 3.5 Store treasury withdrawal amounts as reward_rest.
        //     Only for PREVIOUSLY ratified proposals that are now being enacted.
        Map<GovActionId, GovActionRecord> allProposals = governanceStore.getAllActiveProposals();
        if (rewardRestStore != null) {
            Map<String, BigInteger> aggregatedWithdrawals = new java.util.HashMap<>();
            for (GovActionId id : pendingEnactmentIds) {
                GovActionRecord enactedProposal = allProposals.get(id);
                if (enactedProposal != null && enactedProposal.govAction()
                        instanceof com.bloxbean.cardano.yaci.core.model.governance.actions.TreasuryWithdrawalsAction twa) {
                    if (twa.getWithdrawals() != null) {
                        for (var entry : twa.getWithdrawals().entrySet()) {
                            aggregatedWithdrawals.merge(entry.getKey(), entry.getValue(), BigInteger::add);
                        }
                    }
                }
            }
            for (var entry : aggregatedWithdrawals.entrySet()) {
                boolean stored = rewardRestStore.storeRewardRest(
                        newEpoch,
                        com.bloxbean.cardano.yaci.node.ledgerstate.DefaultAccountStateStore.REWARD_REST_TREASURY_WITHDRAWAL,
                        entry.getKey(), entry.getValue(), previousEpoch, 0,
                        batch, deltaOps);
                if (!stored) {
                    treasuryDelta = treasuryDelta.add(entry.getValue());
                    log.info("Treasury withdrawal to {} unclaimed, returned to treasury",
                            entry.getKey().substring(0, Math.min(16, entry.getKey().length())));
                }
            }
        }

        // 4. Two-phase deposit refunds and proposal removal.
        Set<GovActionId> proposalsToDrop = proposalDropService.computeProposalsToDrop(results, activeProposals);
        BigInteger depositRefunds = BigInteger.ZERO;
        Map<String, BigInteger> aggregatedRefunds = new java.util.HashMap<>();

        // 4a. Process ENACTED proposals (previously ratified → now enact): refund + remove
        for (GovActionId id : pendingEnactmentIds) {
            GovActionRecord proposal = allProposals.get(id);
            if (proposal != null) {
                BigInteger deposit = proposal.deposit();
                depositRefunds = depositRefunds.add(deposit);
                if (deposit.signum() > 0) {
                    aggregatedRefunds.merge(proposal.returnAddress(), deposit, BigInteger::add);
                }
                governanceStore.removeProposal(id, batch, deltaOps);
                governanceStore.removeVotesForProposal(id.getTransactionId(),
                        id.getGov_action_index(), batch, deltaOps);
            }
        }

        // 4b. Process DROPPED proposals (previously expired → now drop): refund + remove
        for (GovActionId id : pendingDropIds) {
            GovActionRecord proposal = allProposals.get(id);
            if (proposal != null) {
                BigInteger deposit = proposal.deposit();
                depositRefunds = depositRefunds.add(deposit);
                if (deposit.signum() > 0) {
                    aggregatedRefunds.merge(proposal.returnAddress(), deposit, BigInteger::add);
                }
                governanceStore.removeProposal(id, batch, deltaOps);
                governanceStore.removeVotesForProposal(id.getTransactionId(),
                        id.getGov_action_index(), batch, deltaOps);
            }
        }

        // Clear processed pending enactments/drops BEFORE storing new ones
        governanceStore.clearPending(batch, deltaOps);

        // 4c. Drop siblings/descendants of enacted proposals
        for (GovActionId dropId : proposalsToDrop) {
            GovActionRecord dropped = activeProposals.get(dropId);
            if (dropped != null) {
                BigInteger deposit = dropped.deposit();
                depositRefunds = depositRefunds.add(deposit);
                if (deposit.signum() > 0) {
                    aggregatedRefunds.merge(dropped.returnAddress(), deposit, BigInteger::add);
                }
                governanceStore.removeProposal(dropId, batch, deltaOps);
                governanceStore.removeVotesForProposal(dropId.getTransactionId(),
                        dropId.getGov_action_index(), batch, deltaOps);
            }
        }

        // 4d. Store NEWLY expired proposals as pending drops for next boundary
        for (RatificationResult result : results) {
            if (result.isExpired()) {
                governanceStore.storePendingDrop(result.govActionId(), batch, deltaOps);
            }
        }

        // Store aggregated refunds as reward_rest
        BigInteger unclaimedRefunds = BigInteger.ZERO;
        if (rewardRestStore != null) {
            for (var entry : aggregatedRefunds.entrySet()) {
                boolean stored = rewardRestStore.storeRewardRest(
                        newEpoch,
                        com.bloxbean.cardano.yaci.node.ledgerstate.DefaultAccountStateStore.REWARD_REST_PROPOSAL_REFUND,
                        entry.getKey(), entry.getValue(), previousEpoch, 0,
                        batch, deltaOps);
                if (!stored) {
                    unclaimedRefunds = unclaimedRefunds.add(entry.getValue());
                }
            }
        }

        if (unclaimedRefunds.signum() > 0) {
            treasuryDelta = treasuryDelta.add(unclaimedRefunds);
            log.info("Unclaimed proposal deposit refunds going to treasury: {}", unclaimedRefunds);
        }

        // 5. Dormant epoch tracking (needed before DRep expiry calculation)
        int remainingCount = activeProposals.size();
        for (RatificationResult result : results) {
            if (result.isRatified() || result.isExpired()) {
                if (activeProposals.containsKey(result.govActionId())) remainingCount--;
            }
        }
        boolean epochHadActiveProposals = remainingCount > 0;
        governanceStore.storeEpochHadActiveProposals(newEpoch, epochHadActiveProposals, batch, deltaOps);

        Set<Integer> dormantEpochs = governanceStore.getDormantEpochs();
        if (!epochHadActiveProposals) {
            dormantEpochs.add(newEpoch);
        }
        if (conwayFirstEpoch >= 0 && newEpoch == conwayFirstEpoch && !dormantEpochs.contains(newEpoch)) {
            dormantEpochs.add(newEpoch);
        }
        governanceStore.storeDormantEpochs(dormantEpochs, batch, deltaOps);

        // 6. Update DRep expiry (after dormant tracking so dormant set is current)
        updateDRepExpiry(newEpoch, dormantEpochs, batch, deltaOps);

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

    // ===== Active DRep Keys =====

    /**
     * Build set of ACTIVE (non-expired) DRep keys from the distribution.
     * Only DReps in the distribution with delegated stake are included.
     */
    private Set<DRepDistKey> buildActiveDRepKeys(Map<DRepDistKey, BigInteger> drepDist, int newEpoch) {
        Set<DRepDistKey> activeDRepKeys = new java.util.HashSet<>();
        try {
            Set<Integer> currentDormantEpochs = governanceStore.getDormantEpochs();
            int drepActivityParam = paramProvider.getDRepActivity(newEpoch);
            int eraFirst = conwayFirstEpoch >= 0 ? conwayFirstEpoch : newEpoch;
            int govLifetime = paramProvider.getGovActionLifetime(newEpoch);

            var allDRepStates = governanceStore.getAllDRepStates();
            for (var distKey : drepDist.keySet()) {
                if (distKey.drepType() > 1) continue; // Skip virtual DReps (abstain, no_confidence)
                CredentialKey ck = new CredentialKey(distKey.drepType(), distKey.drepHash());
                DRepStateRecord rec = allDRepStates.get(ck);
                if (rec == null) continue;
                DRepExpiryCalculator.ProposalSubmissionInfo proposalForDRep =
                        findLatestProposalUpToSlot(rec.registeredAtSlot());
                int expiry = drepExpiryCalculator.calculateExpiry(rec, currentDormantEpochs,
                        drepActivityParam, eraFirst, newEpoch, proposalForDRep, govLifetime);
                if (expiry >= newEpoch) {
                    activeDRepKeys.add(distKey);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to build active DRep set: {}", e.getMessage());
        }
        return activeDRepKeys;
    }

    // ===== DRep Expiry Update =====

    private void updateDRepExpiry(int newEpoch, Set<Integer> dormantEpochs,
                                   WriteBatch batch, List<DeltaOp> deltaOps)
            throws RocksDBException {
        // Per yaci-store DRepExpiryUtil (matches DBSync exactly):
        // expiry = lastActivityEpoch + drepActivity + dormantCount [+ v9Bonus]
        int drepActivity = paramProvider.getDRepActivity(newEpoch);
        int eraFirstEpoch = conwayFirstEpoch >= 0 ? conwayFirstEpoch : newEpoch;
        int govActionLifetime = paramProvider.getGovActionLifetime(newEpoch);

        Map<CredentialKey, DRepStateRecord> allDReps = governanceStore.getActiveDRepStates();
        for (var entry : allDReps.entrySet()) {
            CredentialKey ck = entry.getKey();
            DRepStateRecord state = entry.getValue();

            // Find the latest proposal submitted at or before this DRep's registration slot
            DRepExpiryCalculator.ProposalSubmissionInfo proposalForDRep =
                    findLatestProposalUpToSlot(state.registeredAtSlot());

            int expiry = drepExpiryCalculator.calculateExpiry(state, dormantEpochs, drepActivity,
                    eraFirstEpoch, newEpoch, proposalForDRep, govActionLifetime);
            boolean active = expiry >= newEpoch;

            if (expiry != state.expiryEpoch() || active != state.active()) {
                DRepStateRecord updated = state.withExpiry(expiry, active);
                governanceStore.storeDRepState(ck.credType(), ck.hash(), updated, batch, deltaOps);
            }
        }
    }

    /**
     * Find the latest governance proposal submitted at or before the given slot.
     * Uses permanent PREFIX_PROPOSAL_SUBMISSION store (survives proposal removal).
     */
    private DRepExpiryCalculator.ProposalSubmissionInfo findLatestProposalUpToSlot(long maxSlot) {
        try {
            return governanceStore.findLatestProposalUpToSlot(maxSlot);
        } catch (Exception e) {
            return null;
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

    private Map<GovActionType, BigDecimal> resolveDRepThresholds(boolean isBootstrapPhase, int epoch) {
        Map<GovActionType, BigDecimal> thresholds = new HashMap<>();
        if (isBootstrapPhase) {
            for (GovActionType type : GovActionType.values()) {
                thresholds.put(type, BigDecimal.ZERO);
            }
            return thresholds;
        }

        // Read from protocol params (paramTracker or genesis defaults)
        ProtocolParamUpdate params = (paramTracker instanceof EpochParamTracker ept)
                ? ept.getResolvedParams(epoch) : null;
        if (params != null && params.getDrepVotingThresholds() != null) {
            var dt = params.getDrepVotingThresholds();
            thresholds.put(GovActionType.NO_CONFIDENCE,
                    ProtocolParamGroupClassifier.ratioToBigDecimal(dt.getDvtMotionNoConfidence()));
            thresholds.put(GovActionType.UPDATE_COMMITTEE,
                    ProtocolParamGroupClassifier.ratioToBigDecimal(dt.getDvtCommitteeNormal()));
            thresholds.put(GovActionType.NEW_CONSTITUTION,
                    ProtocolParamGroupClassifier.ratioToBigDecimal(dt.getDvtUpdateToConstitution()));
            thresholds.put(GovActionType.HARD_FORK_INITIATION_ACTION,
                    ProtocolParamGroupClassifier.ratioToBigDecimal(dt.getDvtHardForkInitiation()));
            thresholds.put(GovActionType.TREASURY_WITHDRAWALS_ACTION,
                    ProtocolParamGroupClassifier.ratioToBigDecimal(dt.getDvtTreasuryWithdrawal()));
            // ParameterChange: per-proposal threshold computed in RatificationEngine
            // Use max possible (governance group = 0.75) as default; actual is computed per proposal
            thresholds.put(GovActionType.PARAMETER_CHANGE_ACTION,
                    ProtocolParamGroupClassifier.ratioToBigDecimal(dt.getDvtPPGovGroup()));
        } else {
            // Fallback defaults
            thresholds.put(GovActionType.NO_CONFIDENCE, new BigDecimal("0.67"));
            thresholds.put(GovActionType.UPDATE_COMMITTEE, new BigDecimal("0.67"));
            thresholds.put(GovActionType.NEW_CONSTITUTION, new BigDecimal("0.75"));
            thresholds.put(GovActionType.HARD_FORK_INITIATION_ACTION, new BigDecimal("0.60"));
            thresholds.put(GovActionType.PARAMETER_CHANGE_ACTION, new BigDecimal("0.67"));
            thresholds.put(GovActionType.TREASURY_WITHDRAWALS_ACTION, new BigDecimal("0.67"));
        }
        return thresholds;
    }

    private Map<GovActionType, BigDecimal> resolveSPOThresholds(int epoch) {
        Map<GovActionType, BigDecimal> thresholds = new HashMap<>();
        ProtocolParamUpdate params = (paramTracker instanceof EpochParamTracker ept)
                ? ept.getResolvedParams(epoch) : null;
        if (params != null && params.getPoolVotingThresholds() != null) {
            var pt = params.getPoolVotingThresholds();
            thresholds.put(GovActionType.NO_CONFIDENCE,
                    ProtocolParamGroupClassifier.ratioToBigDecimal(pt.getPvtMotionNoConfidence()));
            thresholds.put(GovActionType.UPDATE_COMMITTEE,
                    ProtocolParamGroupClassifier.ratioToBigDecimal(pt.getPvtCommitteeNormal()));
            thresholds.put(GovActionType.HARD_FORK_INITIATION_ACTION,
                    ProtocolParamGroupClassifier.ratioToBigDecimal(pt.getPvtHardForkInitiation()));
            thresholds.put(GovActionType.PARAMETER_CHANGE_ACTION,
                    ProtocolParamGroupClassifier.ratioToBigDecimal(pt.getPvtPPSecurityGroup()));
        } else {
            thresholds.put(GovActionType.NO_CONFIDENCE, new BigDecimal("0.60"));
            thresholds.put(GovActionType.UPDATE_COMMITTEE, new BigDecimal("0.51"));
            thresholds.put(GovActionType.HARD_FORK_INITIATION_ACTION, new BigDecimal("0.51"));
            thresholds.put(GovActionType.PARAMETER_CHANGE_ACTION, new BigDecimal("0.51"));
        }
        return thresholds;
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
