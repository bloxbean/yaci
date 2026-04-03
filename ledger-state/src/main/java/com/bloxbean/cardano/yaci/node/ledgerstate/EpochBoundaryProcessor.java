package com.bloxbean.cardano.yaci.node.ledgerstate;

import com.bloxbean.cardano.yaci.node.api.EpochParamProvider;
import com.bloxbean.cardano.yaci.node.ledgerstate.UtxoBalanceAggregator;
import com.bloxbean.cardano.yaci.node.ledgerstate.export.EpochSnapshotExporter;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.epoch.GovernanceEpochProcessor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cardanofoundation.rewards.calculation.domain.EpochCalculationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates epoch boundary processing across the three-phase epoch transition sequence
 * that mirrors the Cardano ledger spec's EPOCH rule (shelley-ledger.pdf §17.4):
 *
 * <ol>
 *   <li>{@link #processEpochBoundary} — Reward calculation, AdaPot update, param finalization
 *       (called from {@code PreEpochTransitionEvent})</li>
 *   <li><em>SNAP (delegation snapshot)</em> — handled directly by {@code DefaultAccountStateStore}
 *       (called from {@code EpochTransitionEvent})</li>
 *   <li>{@link #processPostEpochBoundary} — <b>POOLREAP</b>: pool deposit refunds
 *       (called from {@code PostEpochTransitionEvent})</li>
 * </ol>
 *
 * <p>Each subsystem is independently enabled/disabled via configuration.</p>
 */
public class EpochBoundaryProcessor {
    private static final Logger log = LoggerFactory.getLogger(EpochBoundaryProcessor.class);

    private final AdaPotTracker adaPotTracker;
    private final EpochRewardCalculator rewardCalculator;
    private final EpochParamTracker paramTracker;
    private final EpochParamProvider paramProvider;
    private final long networkMagic;

    // Optional governance epoch processor (null = disabled, set after construction)
    private volatile GovernanceEpochProcessor governanceEpochProcessor;

    // Snapshot creator — creates the delegation snapshot between rewards and governance
    private volatile DefaultAccountStateStore snapshotCreator;

    // Expected AdaPot values for verification (loaded lazily from classpath JSON)
    private volatile Map<Integer, ExpectedAdaPot> expectedAdaPots;

    // Optional epoch snapshot exporter for debugging (NOOP when disabled)
    private EpochSnapshotExporter snapshotExporter = EpochSnapshotExporter.NOOP;

    // If true, System.exit(1) on AdaPot verification failure (development mode).
    // If false, log error and record for REST query (production mode).
    private boolean exitOnEpochCalcError = false;

    // Last verification error (null = OK). Queryable via REST endpoint.
    private volatile VerificationError lastVerificationError;

    public record VerificationError(int epoch, java.math.BigInteger expectedTreasury,
                                     java.math.BigInteger actualTreasury, java.math.BigInteger treasuryDiff,
                                     java.math.BigInteger expectedReserves, java.math.BigInteger actualReserves,
                                     java.math.BigInteger reservesDiff) {}

    public EpochBoundaryProcessor(AdaPotTracker adaPotTracker,
                                  EpochRewardCalculator rewardCalculator,
                                  EpochParamTracker paramTracker,
                                  EpochParamProvider paramProvider,
                                  long networkMagic) {
        this.adaPotTracker = adaPotTracker;
        this.rewardCalculator = rewardCalculator;
        this.paramTracker = paramTracker;
        this.paramProvider = paramProvider;
        this.networkMagic = networkMagic;
    }

    /**
     * Set the governance epoch processor for Conway-era governance state tracking.
     */
    public void setGovernanceEpochProcessor(GovernanceEpochProcessor processor) {
        this.governanceEpochProcessor = processor;
    }

    /**
     * Set the snapshot creator for creating delegation snapshots between rewards and governance.
     */
    public void setSnapshotCreator(DefaultAccountStateStore store) {
        this.snapshotCreator = store;
    }

    /**
     * If true, System.exit(1) on AdaPot verification failure (useful during development).
     * If false (default), log the error and continue syncing.
     */
    public void setExitOnEpochCalcError(boolean flag) {
        this.exitOnEpochCalcError = flag;
    }

    /**
     * Returns the last AdaPot verification error, or null if all verifications passed.
     */
    public VerificationError getLastVerificationError() {
        return lastVerificationError;
    }

    /**
     * Set the epoch snapshot exporter for debugging data export.
     * Propagates to the governance epoch processor if present.
     */
    public void setSnapshotExporter(EpochSnapshotExporter exporter) {
        this.snapshotExporter = exporter != null ? exporter : EpochSnapshotExporter.NOOP;
        if (governanceEpochProcessor != null) {
            governanceEpochProcessor.setSnapshotExporter(this.snapshotExporter);
        }
    }

    /**
     * Process an epoch boundary transition from {@code previousEpoch} to {@code newEpoch}.
     */
    public void processEpochBoundary(int previousEpoch, int newEpoch) {
        log.info("Processing epoch boundary: {} → {}", previousEpoch, newEpoch);
        long start = System.currentTimeMillis();

        // 1. Finalize protocol parameters for the new epoch
        if (paramTracker != null && paramTracker.isEnabled()) {
            paramTracker.finalizeEpoch(newEpoch);
        }

        // Log effective params for verification against yaci-store epoch_param
        EpochParamProvider effectiveParams = (paramTracker != null && paramTracker.isEnabled())
                ? paramTracker : paramProvider;
        log.info("Epoch {} params: protoVer={}.{}, d={}, nOpt={}, rho={}, tau={}, a0={}, minPoolCost={}",
                newEpoch, effectiveParams.getProtocolMajor(newEpoch), effectiveParams.getProtocolMinor(newEpoch),
                effectiveParams.getDecentralization(newEpoch), effectiveParams.getNOpt(newEpoch),
                effectiveParams.getRho(newEpoch), effectiveParams.getTau(newEpoch),
                effectiveParams.getA0(newEpoch), effectiveParams.getMinPoolCost(newEpoch));

        // 2. Bootstrap AdaPot at the Shelley start epoch (before any reward calculation)
        bootstrapAdaPotIfNeeded(newEpoch);

        // 3. Calculate rewards FIRST (matches yaci-store: rewards → snapshot → governance)
        if (rewardCalculator != null && rewardCalculator.isEnabled() && newEpoch >= 2) {
            calculateAndStoreRewards(previousEpoch, newEpoch, null);
        }

        // 4. SNAP: Create delegation snapshot (captures post-reward state).
        //    Returns UTXO balances for reuse in DRep distribution calculation.
        java.util.Map<UtxoBalanceAggregator.CredentialKey, java.math.BigInteger> utxoBalances = null;
        if (snapshotCreator != null) {
            utxoBalances = snapshotCreator.createAndCommitDelegationSnapshot(previousEpoch);
        }

        // 4b. POOLREAP: Pool deposit refunds (after snapshot, before governance).
        //     Per Amaru (state.rs): Rewards → Snapshot → tick_pools(POOLREAP) → tick_proposals(governance)
        //     POOLREAP credits go into reward balances, visible to DRep distribution.
        if (rewardCalculator != null && rewardCalculator.isEnabled()) {
            rewardCalculator.processPoolDepositRefunds(newEpoch);
        }

        // Get spendable reward_rest for DRep distribution (matches snapshot's reward_rest inclusion)
        java.util.Map<String, java.math.BigInteger> spendableRewardRest = null;
        if (snapshotCreator != null) {
            spendableRewardRest = snapshotCreator.getSpendableRewardRest(previousEpoch);
        }

        // 5. Conway governance epoch processing (ratify, enact, expire, refund)
        //    Passes UTXO balances and reward_rest so DRep distribution matches snapshot composition.
        GovernanceEpochProcessor.GovernanceEpochResult govResult = null;
        if (governanceEpochProcessor != null) {
            try {
                govResult = governanceEpochProcessor.processEpochBoundaryAndCommit(
                        previousEpoch, newEpoch, utxoBalances, spendableRewardRest);
            } catch (Exception e) {
                log.error("Governance epoch processing failed for {} → {}: {}",
                        previousEpoch, newEpoch, e.getMessage());
            }
        }

        // 6. Apply governance treasury delta to AdaPot (post-reward adjustment)
        if (govResult != null && adaPotTracker != null && adaPotTracker.isEnabled()) {
            BigInteger govTreasuryDelta = govResult.treasuryDelta().add(govResult.donations());
            if (govTreasuryDelta.signum() != 0) {
                var currentPot = adaPotTracker.getAdaPot(newEpoch);
                if (currentPot.isPresent()) {
                    var pot = currentPot.get();
                    BigInteger adjustedTreasury = pot.treasury().add(govTreasuryDelta);
                    adaPotTracker.storeAdaPot(newEpoch,
                            new AccountStateCborCodec.AdaPot(adjustedTreasury, pot.reserves(),
                                    pot.deposits(), pot.fees(), pot.distributed(),
                                    pot.undistributed(), pot.rewardsPot(), pot.poolRewardsPot()));
                    log.info("Governance adjusted treasury for epoch {}: delta={} (withdrawals={}, donations={})",
                            newEpoch, govTreasuryDelta, govResult.treasuryDelta(), govResult.donations());
                }
            }
        }

        // 7. Verify final AdaPot (after both reward calculation and governance adjustment)
        if (adaPotTracker != null && adaPotTracker.isEnabled() && newEpoch >= 2) {
            var finalPot = adaPotTracker.getAdaPot(newEpoch);
            if (finalPot.isPresent()) {
                verifyAdaPot(newEpoch, finalPot.get().treasury(), finalPot.get().reserves());

                // Export AdaPot snapshot for debugging
                if (snapshotExporter != EpochSnapshotExporter.NOOP) {
                    var p = finalPot.get();
                    snapshotExporter.exportAdaPot(newEpoch, new EpochSnapshotExporter.AdaPotEntry(
                            newEpoch, p.treasury(), p.reserves(), p.deposits(), p.fees(),
                            p.distributed(), p.undistributed(), p.rewardsPot(), p.poolRewardsPot()));
                }
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("Epoch boundary processing complete ({} → {}) in {}ms", previousEpoch, newEpoch, elapsed);
    }

    /**
     * Process post-epoch boundary.
     * POOLREAP is now done in processEpochBoundary (after snapshot, before governance)
     * matching the Haskell/Amaru order: Snapshot → POOLREAP → Governance.
     */
    public void processPostEpochBoundary(int newEpoch) {
        // POOLREAP moved to processEpochBoundary step 4b
    }

    /**
     * Run reward calculation and update AdaPot with the results.
     * Called BEFORE governance — governance treasury delta is applied as post-reward adjustment.
     */
    private void calculateAndStoreRewards(int previousEpoch, int newEpoch,
                                          GovernanceEpochProcessor.GovernanceEpochResult govResult) {
        // Get previous AdaPot
        BigInteger prevTreasury = BigInteger.ZERO;
        BigInteger prevReserves = BigInteger.ZERO;

        if (adaPotTracker != null && adaPotTracker.isEnabled()) {
            var prevPot = adaPotTracker.getAdaPot(previousEpoch);
            if (prevPot.isEmpty()) {
                prevPot = adaPotTracker.getLatestAdaPot(previousEpoch);
                if (prevPot.isPresent()) {
                    log.info("Using latest available AdaPot (not epoch {}) as previous", previousEpoch);
                }
            }
            if (prevPot.isPresent()) {
                prevTreasury = prevPot.get().treasury();
                prevReserves = prevPot.get().reserves();
            } else {
                log.warn("No AdaPot found for previous epoch {}, using zeros", previousEpoch);
            }
        }

        // Resolve param provider (prefer tracker if available)
        EpochParamProvider effectiveParams = (paramTracker != null && paramTracker.isEnabled())
                ? paramTracker : paramProvider;

        // Calculate and distribute rewards
        Optional<EpochCalculationResult> resultOpt = rewardCalculator.calculateAndDistribute(
                newEpoch, prevTreasury, prevReserves, effectiveParams, networkMagic);

        // Store updated AdaPot
        if (resultOpt.isPresent() && adaPotTracker != null && adaPotTracker.isEnabled()) {
            var result = resultOpt.get();

            var newPot = new AccountStateCborCodec.AdaPot(
                    result.getTreasury(),
                    result.getReserves(),
                    BigInteger.ZERO, // deposits tracked separately
                    rewardCalculator.getEpochFees(newEpoch - 1),
                    result.getTotalDistributedRewards(),
                    result.getTotalUndistributedRewards() != null
                            ? result.getTotalUndistributedRewards() : BigInteger.ZERO,
                    result.getTotalRewardsPot() != null
                            ? result.getTotalRewardsPot() : BigInteger.ZERO,
                    result.getTotalPoolRewardsPot() != null
                            ? result.getTotalPoolRewardsPot() : BigInteger.ZERO
            );
            adaPotTracker.storeAdaPot(newEpoch, newPot);
            // Note: verification moved to processEpochBoundary() after governance adjustment
        }
    }

    /**
     * Verify calculated AdaPot against expected values from classpath JSON.
     * Only runs when: (1) expected JSON file exists for this network, AND (2) epoch has an entry.
     * Any mismatch (even 1 lovelace) is an error.
     */
    private void verifyAdaPot(int epoch, BigInteger treasury, BigInteger reserves) {
        var expected = getExpectedAdaPots();
        if (expected == null || expected.isEmpty()) return; // no expected file for this network

        var exp = expected.get(epoch);
        if (exp == null) return; // no expected data for this epoch — skip silently

        BigInteger treasuryDiff = treasury.subtract(exp.treasury);
        BigInteger reservesDiff = reserves.subtract(exp.reserves);

        if (treasuryDiff.signum() == 0 && reservesDiff.signum() == 0) {
            log.info("AdaPot verification PASSED for epoch {}", epoch);
            return;
        }

        // Mismatch detected
        log.error("AdaPot verification FAILED for epoch {}!", epoch);
        if (treasuryDiff.signum() != 0) {
            log.error("  Treasury: expected={}, actual={}, diff={}", exp.treasury, treasury, treasuryDiff);
        }
        if (reservesDiff.signum() != 0) {
            log.error("  Reserves: expected={}, actual={}, diff={}", exp.reserves, reserves, reservesDiff);
        }

        lastVerificationError = new VerificationError(epoch,
                exp.treasury, treasury, treasuryDiff, exp.reserves, reserves, reservesDiff);

        if (exitOnEpochCalcError) {
            log.error("Exiting (exit-on-epoch-calc-error=true). Debug epoch {} before continuing.", epoch);
            System.exit(1);
        } else {
            log.error("Continuing despite mismatch (exit-on-epoch-calc-error=false). " +
                    "Check /api/v1/node/epoch-calc-status for details.");
        }
    }

    private Map<Integer, ExpectedAdaPot> getExpectedAdaPots() {
        if (expectedAdaPots != null) return expectedAdaPots;

        String filename = switch ((int) networkMagic) {
            case 1 -> "expected_ada_pots_preprod.json";
            case 2 -> "expected_ada_pots_preview.json";
            case 764824073 -> "expected_ada_pots_mainnet.json";
            default -> null;
        };

        if (filename == null) {
            expectedAdaPots = Map.of();
            return expectedAdaPots;
        }

        try (var is = getClass().getClassLoader().getResourceAsStream(filename)) {
            if (is == null) {
                log.info("No expected AdaPot file found: {}", filename);
                expectedAdaPots = Map.of();
                return expectedAdaPots;
            }
            var mapper = new ObjectMapper();
            List<Map<String, Object>> pots = mapper.readValue(is, new TypeReference<>() {});
            var map = new ConcurrentHashMap<Integer, ExpectedAdaPot>();
            for (var pot : pots) {
                int epochNo = ((Number) pot.get("epoch_no")).intValue();
                BigInteger t = new BigInteger(pot.get("treasury").toString());
                BigInteger r = new BigInteger(pot.get("reserves").toString());
                map.put(epochNo, new ExpectedAdaPot(t, r));
            }
            expectedAdaPots = map;
            log.info("Loaded {} expected AdaPot entries from {}", map.size(), filename);
        } catch (Exception e) {
            log.warn("Failed to load expected AdaPot file {}: {}", filename, e.getMessage());
            expectedAdaPots = Map.of();
        }
        return expectedAdaPots;
    }

    private record ExpectedAdaPot(BigInteger treasury, BigInteger reserves) {}

    /**
     * Bootstrap AdaPot at the Shelley start epoch using the cf-rewards NetworkConfig
     * initial reserves and treasury values. This must run once before any reward calculation.
     */
    private void bootstrapAdaPotIfNeeded(int newEpoch) {
        if (adaPotTracker == null || !adaPotTracker.isEnabled()) return;

        // Only bootstrap if no AdaPot exists yet for any epoch
        var existing = adaPotTracker.getLatestAdaPot(newEpoch);
        if (existing.isPresent()) return;

        // Use cf-rewards NetworkConfig to get initial reserves/treasury for this network
        var networkConfig = EpochRewardCalculator.resolveNetworkConfig(networkMagic);
        int shelleyStartEpoch = networkConfig.getShelleyStartEpoch();

        BigInteger initialReserves = networkConfig.getShelleyInitialReserves();
        BigInteger initialTreasury = networkConfig.getShelleyInitialTreasury();

        if (initialReserves == null) initialReserves = BigInteger.ZERO;
        if (initialTreasury == null) initialTreasury = BigInteger.ZERO;

        var pot = new AccountStateCborCodec.AdaPot(
                initialTreasury, initialReserves,
                BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO,
                BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO);
        adaPotTracker.storeAdaPot(shelleyStartEpoch, pot);
        log.info("AdaPot bootstrapped at shelley start epoch {}: treasury={}, reserves={}",
                shelleyStartEpoch, initialTreasury, initialReserves);
    }
}
