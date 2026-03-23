package com.bloxbean.cardano.yaci.node.app.e2e.haskellsync;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenario 1: Regular block-producer mode.
 * Starts Yaci devnet producing blocks, connects a Haskell cardano-node,
 * and verifies sync stays in lock-step for 2+ epochs.
 *
 * Devnet config: epochLength=600, slotLength=0.2s → 1 epoch = 120s.
 */
public class RegularBPSyncTest extends HaskellSyncTestBase {

    private static final Logger log = LoggerFactory.getLogger(RegularBPSyncTest.class);

    @Test
    void haskellSyncsInRegularBPMode() throws Exception {
        // 1. Start Yaci in regular devnet mode
        yaci = new YaciNodeManager(tempDir, uberJarPath);
        yaci.start();
        yaci.waitForReady(60_000);

        // 2. Wait for some blocks to be produced
        Thread.sleep(3000);

        var tip = yaci.getTip();
        log.info("Yaci tip after startup: slot={}, block={}", tip.get("slot"), tip.get("blockNumber"));
        assertTrue(tip.get("slot").asLong() > 0, "Yaci should have produced blocks");

        // 3. Copy genesis files and start Haskell node
        haskell = new CardanoNodeManager(tempDir);
        yaci.copyGenesisTo(haskell.getGenesisDir());
        haskell.start(yaci.getN2nPort());

        // 4. Wait for Haskell to sync to current Yaci tip
        long currentSlot = tip.get("slot").asLong();
        haskell.waitForChainExtended(currentSlot, 60_000);
        log.info("Haskell node synced to slot {}", currentSlot);

        // 5. Wait for 2 full epochs (epochLength=600 slots, slotLength=0.2s → 120s per epoch)
        // Target: slot 1200 (2 epochs)
        long twoEpochSlot = 1200;
        long twoEpochTimeoutMs = 300_000; // 5 minutes to be safe
        log.info("Waiting for Haskell node to reach slot {} (2 epochs)...", twoEpochSlot);
        haskell.waitForChainExtended(twoEpochSlot, twoEpochTimeoutMs);

        // 6. Assert still in sync
        assertTipsSynced(10);
        log.info("Regular BP sync test passed — Haskell node in sync for 2+ epochs");
    }
}
