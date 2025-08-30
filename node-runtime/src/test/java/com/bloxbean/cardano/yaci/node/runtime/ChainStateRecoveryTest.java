package com.bloxbean.cardano.yaci.node.runtime;

import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.node.runtime.chain.DirectRocksDBChainState;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Recovery test for corrupted chainstate.
 * This test directly opens the actual chainstate and performs recovery operations.
 *
 * IMPORTANT: Only run this test when the yaci node is completely stopped!
 */
@Slf4j
public class ChainStateRecoveryTest {

    // Path from your application.properties configuration
    private static final String CHAINSTATE_PATH = "/Volumes/data2/work/chainstate";

    private DirectRocksDBChainState chainState;

    @BeforeEach
    void setUp() {
        log.info("🔧 Setting up ChainState recovery test...");
        log.info("📁 Using chainstate path: {}", CHAINSTATE_PATH);

        try {
            // Create DirectRocksDBChainState with your actual chainstate
            chainState = new DirectRocksDBChainState(CHAINSTATE_PATH);
            log.info("✅ ChainState initialized successfully");
        } catch (Exception e) {
            log.error("❌ Failed to initialize ChainState", e);
            if (e.getMessage().contains("lock") || e.getMessage().contains("LOCK")) {
                log.error("🚨 DATABASE LOCK ERROR - Make sure the yaci node is completely stopped!");
                log.error("   Try: pkill -f yaci  or  ps aux | grep yaci");
            }
            throw e;
        }
    }

    @AfterEach
    void tearDown() {
        if (chainState != null) {
            log.info("🔒 Closing chainstate connection...");
            chainState.close();
            log.info("✅ ChainState closed successfully");
        }
    }

    /**
     * Test to detect and recover from chainstate corruption.
     * Remove @Disabled annotation when you want to run the actual recovery.
     */
    @Test
  //  @Disabled("Remove @Disabled to run actual recovery on your chainstate")
    void testRecoverCorruptedChainState() {
        log.info("🔍 Starting chainstate corruption detection and recovery test...");

        try {
            // Step 1: Get current state information
            ChainTip currentTip = chainState.getTip();
            ChainTip currentHeaderTip = chainState.getHeaderTip();

            log.info("📊 Current chain state:");
            log.info("   Body Tip: {}", formatChainTip(currentTip));
            log.info("   Header Tip: {}", formatChainTip(currentHeaderTip));

            // Step 2: Detect corruption
            log.info("🔍 Detecting corruption...");
            boolean hasCorruption = chainState.detectCorruption();

            if (hasCorruption) {
                log.warn("🚨 CORRUPTION DETECTED!");
                log.info("🔧 Starting recovery process...");

                // Step 3: Perform recovery
                chainState.recoverFromCorruption();

                log.info("✅ Recovery completed!");

                // Step 4: Verify recovery worked
                log.info("🔍 Verifying recovery...");
                boolean stillCorrupted = chainState.detectCorruption();
                assertFalse(stillCorrupted, "Chain state should not be corrupted after recovery");

                // Step 5: Show final state
                ChainTip finalTip = chainState.getTip();
                ChainTip finalHeaderTip = chainState.getHeaderTip();

                log.info("📊 Final chain state after recovery:");
                log.info("   Body Tip: {}", formatChainTip(finalTip));
                log.info("   Header Tip: {}", formatChainTip(finalHeaderTip));

                log.info("🎉 RECOVERY SUCCESSFUL!");

            } else {
                log.info("✅ No corruption detected - chainstate is healthy");
            }

        } catch (Exception e) {
            log.error("❌ Recovery test failed", e);
            fail("Recovery process failed: " + e.getMessage());
        }
    }

    /**
     * Test to just analyze the current chainstate without making any changes.
     * This is safe to run anytime.
     */
    @Test
    void testAnalyzeChainState() {
        log.info("📊 Analyzing current chainstate (read-only)...");

        try {
            // Get current tips
            ChainTip bodyTip = chainState.getTip();
            ChainTip headerTip = chainState.getHeaderTip();

            log.info("📈 Chain State Analysis:");
            log.info("   Body Tip: {}", formatChainTip(bodyTip));
            log.info("   Header Tip: {}", formatChainTip(headerTip));

            // Check for corruption without fixing it
            boolean hasCorruption = chainState.detectCorruption();
            log.info("   Corruption Status: {}", hasCorruption ? "🚨 CORRUPTED" : "✅ HEALTHY");

            if (bodyTip != null && headerTip != null) {
                long gap = headerTip.getBlockNumber() - bodyTip.getBlockNumber();
                log.info("   Block Gap: {} blocks", gap);

                if (gap > 100000) {
                    log.warn("⚠️  Large gap detected between headers and bodies: {} blocks", gap);
                }
            }

            // This test should always pass (it's just analysis)
            assertTrue(true, "Analysis completed");

        } catch (Exception e) {
            log.error("❌ Analysis failed", e);
            fail("Chainstate analysis failed: " + e.getMessage());
        }
    }

    /**
     * Helper method to format ChainTip for logging
     */
    private String formatChainTip(ChainTip tip) {
        if (tip == null) {
            return "null";
        }
        return String.format("Block #%d at slot %d (hash: %s...)",
                tip.getBlockNumber(),
                tip.getSlot(),
                tip.getBlockHash() != null && tip.getBlockHash().length > 0
                    ? bytesToHex(tip.getBlockHash()).substring(0, Math.min(16, bytesToHex(tip.getBlockHash()).length()))
                    : "unknown");
    }

    /**
     * Helper method to convert bytes to hex string
     */
    private String bytesToHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }
}
