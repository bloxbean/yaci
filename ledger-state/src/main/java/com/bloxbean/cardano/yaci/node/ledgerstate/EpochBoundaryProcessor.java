package com.bloxbean.cardano.yaci.node.ledgerstate;

import com.bloxbean.cardano.yaci.node.api.EpochParamProvider;
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

    // Expected AdaPot values for verification (loaded lazily from classpath JSON)
    private volatile Map<Integer, ExpectedAdaPot> expectedAdaPots;

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

        // 3. Calculate rewards (requires AdaPot from previous epoch)
        if (rewardCalculator != null && rewardCalculator.isEnabled() && newEpoch >= 2) {
            calculateAndStoreRewards(previousEpoch, newEpoch);
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("Epoch boundary processing complete ({} → {}) in {}ms", previousEpoch, newEpoch, elapsed);
    }

    /**
     * Process post-epoch boundary: POOLREAP (pool deposit refunds).
     * Called AFTER the delegation snapshot is taken so refunds don't inflate active stake.
     */
    public void processPostEpochBoundary(int newEpoch) {
        if (rewardCalculator != null && rewardCalculator.isEnabled()) {
            log.info("Processing post-epoch boundary (POOLREAP) for epoch {}", newEpoch);
            rewardCalculator.processPoolDepositRefunds(newEpoch);
        }
    }

    /**
     * Run reward calculation and update AdaPot with the results.
     */
    private void calculateAndStoreRewards(int previousEpoch, int newEpoch) {
        // Get previous AdaPot
        BigInteger prevTreasury = BigInteger.ZERO;
        BigInteger prevReserves = BigInteger.ZERO;

        if (adaPotTracker != null && adaPotTracker.isEnabled()) {
            var prevPot = adaPotTracker.getAdaPot(previousEpoch);
            if (prevPot.isEmpty()) {
                // After fast-sync, AdaPot for the previous epoch may not exist.
                // Fall back to the latest available AdaPot (e.g., the bootstrap at epoch 4).
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

            // Verify against expected values (from DBSync/Haskell node ground truth)
            verifyAdaPot(newEpoch, result.getTreasury(), result.getReserves());
        }
    }

    /**
     * Verify calculated AdaPot against expected values from DBSync ground truth.
     * On mismatch, logs error and exits to prevent compounding errors in subsequent epochs.
     */
    private void verifyAdaPot(int epoch, BigInteger treasury, BigInteger reserves) {
        var expected = getExpectedAdaPots();
        if (expected == null || expected.isEmpty()) return;

        var exp = expected.get(epoch);
        if (exp == null) return;

        boolean treasuryMatch = treasury.equals(exp.treasury);
        boolean reservesMatch = reserves.equals(exp.reserves);

        if (treasuryMatch && reservesMatch) {
            log.info("AdaPot verification PASSED for epoch {}", epoch);
        } else {
            log.error("AdaPot verification FAILED for epoch {}!", epoch);
            if (!treasuryMatch) {
                log.error("  Treasury: expected={}, actual={}, diff={}",
                        exp.treasury, treasury, treasury.subtract(exp.treasury));
            }
            if (!reservesMatch) {
                log.error("  Reserves: expected={}, actual={}, diff={}",
                        exp.reserves, reserves, reserves.subtract(exp.reserves));
            }
            log.error("Exiting to prevent compounding errors. Debug epoch {} before continuing.", epoch);
            System.exit(1);
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
