package com.bloxbean.cardano.yaci.node.app.e2e.haskellsync;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Scenario 2: Past-time-travel mode.
 * Starts Yaci devnet with past-time-travel enabled, shifts epochs back,
 * catches up to wall-clock, then starts a Haskell cardano-node and
 * verifies it syncs the entire chain from slot 0.
 */
public class PastTimeTravelSyncTest extends HaskellSyncTestBase {

    private static final Logger log = LoggerFactory.getLogger(PastTimeTravelSyncTest.class);

    @Test
    void haskellSyncsAfterPastTimeTravel() throws Exception {
        // 1. Start Yaci in past-time-travel mode
        yaci = new YaciNodeManager(tempDir, uberJarPath);
        yaci.start(
                "-Dyaci.node.block-producer.past-time-travel-mode=true",
                "-Dyaci.node.block-producer.block-time-millis=0"
        );
        yaci.waitForReady(60_000);

        // 2. Shift 4 epochs back
        log.info("Shifting 4 epochs...");
        JsonNode shiftResult = yaci.post("epochs/shift", "{\"epochs\": 4}");
        log.info("Epoch shift result: {}", shiftResult);
        assertEquals(0, shiftResult.get("genesis_slot").asInt(), "Genesis slot should be 0 after shift");

        // 3. Wait for blocks to be produced in past-time-travel mode (sequential slots)
        Thread.sleep(5000);
        JsonNode tip = yaci.getTip();
        long slot = tip.get("slot").asLong();
        long blockNumber = tip.get("blockNumber").asLong();
        log.info("After epoch shift — slot: {}, blockNumber: {}", slot, blockNumber);
        assertTrue(slot > 0, "Should have produced blocks after epoch shift");
        // In past-time-travel mode, slot == blockNumber (sequential)
        assertEquals(slot, blockNumber, "In past-time-travel mode, slot should equal blockNumber");

        // 4. Catch up to wall-clock time
        log.info("Catching up to wall-clock...");
        JsonNode catchUpResult = yaci.post("epochs/catch-up", "{}");
        log.info("Catch-up result: {}", catchUpResult);
        int blocksProduced = catchUpResult.get("blocks_produced").asInt();
        assertTrue(blocksProduced > 2000, "Catch-up should produce >2000 blocks, got " + blocksProduced);
        log.info("Catch-up produced {} blocks", blocksProduced);

        // 5. Verify wall-clock mode (slot > blockNumber because real time slots are sparse)
        Thread.sleep(3000);
        tip = yaci.getTip();
        long newSlot = tip.get("slot").asLong();
        long newBlockNumber = tip.get("blockNumber").asLong();
        log.info("After catch-up — slot: {}, blockNumber: {}", newSlot, newBlockNumber);
        assertTrue(newSlot > newBlockNumber, "After catch-up, slot should exceed blockNumber");

        // 6. Start Haskell node and verify full sync from slot 0
        haskell = new CardanoNodeManager(tempDir);
        yaci.copyGenesisTo(haskell.getGenesisDir());
        haskell.start(yaci.getN2nPort());

        // Haskell should start syncing from slot 0
        haskell.waitForChainExtended(0, 60_000);
        log.info("Haskell node started syncing (slot 0 extended)");

        // Then catch up to near current tip
        tip = yaci.getTip();
        long targetSlot = tip.get("slot").asLong() - 5;
        log.info("Waiting for Haskell to catch up to slot {}...", targetSlot);
        haskell.waitForChainExtended(targetSlot, 120_000);

        // Assert in sync
        assertTipsSynced(10);
        log.info("Past-time-travel sync test passed — Haskell fully synced from slot 0");
    }
}
