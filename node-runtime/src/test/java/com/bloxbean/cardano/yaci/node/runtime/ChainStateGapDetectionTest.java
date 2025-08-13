package com.bloxbean.cardano.yaci.node.runtime;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.node.runtime.chain.DirectRocksDBChainState;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class ChainStateGapDetectionTest {

    @TempDir
    Path tempDir;

    private ChainState chainState;

    @BeforeEach
    void setUp() {
        // Use the actual RocksDB chainstate from the user's system
        String chainStatePath = "/Users/satya/work/bloxbean/yaci/chainstate";
        chainState = new DirectRocksDBChainState(chainStatePath);
    }

    @AfterEach
    void tearDown() {
        if (chainState instanceof AutoCloseable) {
            try {
                ((AutoCloseable) chainState).close();
            } catch (Exception e) {
                log.error("Error closing chainstate", e);
            }
        }
    }

    /**
     * Test to detect gaps in block storage by sequentially checking blocks
     * This test identifies missing blocks that should exist based on the chain tip
     */
    @Test
    void testSequentialBlockGapDetection() {
        log.info("=== Starting Sequential Block Gap Detection Test ===");
        runGapDetectionTest(chainState);
    }

    /**
     * Test to detect gaps in header storage by sequentially checking headers
     * This test identifies missing headers that should exist based on the header tip
     */
    @Test
    void testSequentialHeaderGapDetection() {
        log.info("=== Starting Sequential Header Gap Detection Test ===");
        runHeaderGapDetectionTest(chainState);
    }

    /**
     * Test to verify block existence by block number or point
     */
    @Test
    void testBlockExistenceVerification() {
        log.info("=== Starting Block Existence Verification Test ===");
        runBlockExistenceTest(chainState);
    }

    private void runGapDetectionTest(ChainState cs) {
        log.info("Running gap detection test for RocksDB chainstate");

        ChainTip tip = cs.getTip();
        if (tip == null) {
            log.warn("No tip found - chainstate is empty");
            return;
        }

        log.info("Chain tip: slot={}, blockNumber={}, hash={}",
                 tip.getSlot(), tip.getBlockNumber(), tip.getBlockHash());

        Point firstBlock = cs.getFirstBlock();
        if (firstBlock == null) {
            log.warn("No first block found");
            return;
        }

        log.info("First block: slot={}, hash={}",
                 firstBlock.getSlot(), firstBlock.getHash());

        List<Long> missingBlocks = new ArrayList<>();
        List<Long> corruptedBlocks = new ArrayList<>();
        long checkedBlocks = 0;

        // Calculate total blocks assuming continuous range
        long totalBlocks = tip.getBlockNumber();

        log.info("Scanning {} blocks sequentially from block 1 to {}...", totalBlocks, tip.getBlockNumber());

        // Start from block 1 and check each block sequentially
        for (Long blockNumber = 1L; blockNumber <= tip.getBlockNumber(); blockNumber++) {
            checkedBlocks++;

            // Check if block exists by block number
            byte[] blockData = cs.getBlockByNumber(blockNumber);
            if (blockData == null) {
                missingBlocks.add(blockNumber);
               // if (missingBlocks.size() <= 10) { // Only log first 10 to avoid spam
                    log.warn("Missing block at blockNumber={}", blockNumber);
                //}
                continue;
            }

            // Validate block data integrity
            if (blockData.length == 0) {
                corruptedBlocks.add(blockNumber);
                if (corruptedBlocks.size() <= 10) { // Only log first 10 to avoid spam
                    log.warn("Corrupted/empty block at blockNumber={}", blockNumber);
                }
                continue;
            }

            if (checkedBlocks % 50000 == 0) {
                log.info("Progress: {}/{} blocks checked ({}%) - Found {} missing, {} corrupted so far",
                        checkedBlocks, totalBlocks,
                        (double) checkedBlocks / totalBlocks * 100,
                        missingBlocks.size(), corruptedBlocks.size());
            }
        }

        // Report results
        log.info("=== Block Gap Detection Results ===");
        log.info("Total blocks checked: {}", checkedBlocks);
        log.info("Missing blocks: {} {}", missingBlocks.size(),
                missingBlocks.size() > 0 ? "(first 20: " +
                (missingBlocks.size() > 20 ? missingBlocks.subList(0, 20) : missingBlocks) + ")" : "");
        log.info("Corrupted blocks: {} {}", corruptedBlocks.size(),
                corruptedBlocks.size() > 0 ? "(first 20: " +
                (corruptedBlocks.size() > 20 ? corruptedBlocks.subList(0, 20) : corruptedBlocks) + ")" : "");

        if (missingBlocks.isEmpty() && corruptedBlocks.isEmpty()) {
            log.info("✅ No gaps found - chainstate is continuous");
        } else {
            log.warn("❌ Found {} missing and {} corrupted blocks",
                    missingBlocks.size(), corruptedBlocks.size());
        }
    }

    private void runHeaderGapDetectionTest(ChainState cs) {
        log.info("Running header gap detection test for RocksDB chainstate");

        ChainTip headerTip = cs.getHeaderTip();
        if (headerTip == null) {
            log.warn("No header tip found");
            return;
        }

        log.info("Header tip: slot={}, blockNumber={}, hash={}",
                 headerTip.getSlot(), headerTip.getBlockNumber(), headerTip.getBlockHash());

        List<Long> missingHeaders = new ArrayList<>();
        List<Long> corruptedHeaders = new ArrayList<>();
        long checkedHeaders = 0;

        long totalHeaders = headerTip.getBlockNumber();
        log.info("Scanning {} headers sequentially from block 1 to {}...", totalHeaders, headerTip.getBlockNumber());

        for (Long blockNumber = 1L; blockNumber <= headerTip.getBlockNumber(); blockNumber++) {
            checkedHeaders++;

            // Check if header exists by block number
            byte[] headerData = cs.getBlockHeaderByNumber(blockNumber);
            if (headerData == null) {
                missingHeaders.add(blockNumber);
//                if (missingHeaders.size() <= 10) { // Only log first 10 to avoid spam
                    log.warn("Missing header at blockNumber={}", blockNumber);
//                }
                continue;
            }

            // Validate header data integrity
            if (headerData.length == 0) {
                corruptedHeaders.add(blockNumber);
                if (corruptedHeaders.size() <= 10) { // Only log first 10 to avoid spam
                    log.warn("Corrupted/empty header at blockNumber={}", blockNumber);
                }
            }

            if (checkedHeaders % 50000 == 0) {
                log.info("Header Progress: {}/{} checked ({}%) - Found {} missing, {} corrupted so far",
                        checkedHeaders, totalHeaders,
                        (double) checkedHeaders / totalHeaders * 100,
                        missingHeaders.size(), corruptedHeaders.size());
            }
        }

        // Report results
        log.info("=== Header Gap Detection Results ===");
        log.info("Total headers checked: {}", checkedHeaders);
        log.info("Missing headers: {} {}", missingHeaders.size(),
                missingHeaders.size() > 0 ? "(first 20: " +
                (missingHeaders.size() > 20 ? missingHeaders.subList(0, 20) : missingHeaders) + ")" : "");
        log.info("Corrupted headers: {} {}", corruptedHeaders.size(),
                corruptedHeaders.size() > 0 ? "(first 20: " +
                (corruptedHeaders.size() > 20 ? corruptedHeaders.subList(0, 20) : corruptedHeaders) + ")" : "");

        if (missingHeaders.isEmpty() && corruptedHeaders.isEmpty()) {
            log.info("✅ No header gaps found - header chainstate is continuous");
        } else {
            log.warn("❌ Found {} missing and {} corrupted headers",
                    missingHeaders.size(), corruptedHeaders.size());
        }
    }

    private void runBlockExistenceTest(ChainState cs) {
        log.info("Running block existence verification test for RocksDB chainstate");

        ChainTip tip = cs.getTip();
        if (tip == null) {
            log.warn("No tip found for existence test");
            return;
        }

        // Test specific block lookups around the problematic area from the log
        List<Long> testBlockNumbers = new ArrayList<>();

        // Add standard test points
        testBlockNumbers.addAll(List.of(
                1L, 100L, 1000L, 10000L, 100000L,
                tip.getBlockNumber() - 100L,
                tip.getBlockNumber() - 10L,
                tip.getBlockNumber()
        ));

        // Add problematic slots from the error log if we can find their block numbers
        // The log shows slot=1844334, 1844358, 1844369, 1844381, etc.
        // We need to test these specific areas
        Long problematicSlot = 1844334L;
        Long blockNumForProblematicSlot = cs.getBlockNumberBySlot(problematicSlot);
        if (blockNumForProblematicSlot != null) {
            // Test around the problematic block
            for (long offset = -5; offset <= 5; offset++) {
                long testBlockNum = blockNumForProblematicSlot + offset;
                if (testBlockNum > 0 && testBlockNum <= tip.getBlockNumber()) {
                    testBlockNumbers.add(testBlockNum);
                }
            }
        }

        log.info("Testing specific block existence for {} block numbers", testBlockNumbers.size());

        int existingBlocks = 0;
        int missingBlocks = 0;

        for (Long blockNumber : testBlockNumbers) {
            if (blockNumber <= 0 || blockNumber > tip.getBlockNumber()) {
                continue; // Skip invalid block numbers
            }

            // Test getBlockByNumber
            byte[] blockData = cs.getBlockByNumber(blockNumber);
            boolean blockExists = (blockData != null && blockData.length > 0);

            // Test getBlockHeaderByNumber
            byte[] headerData = cs.getBlockHeaderByNumber(blockNumber);
            boolean headerExists = (headerData != null && headerData.length > 0);

            log.info("Block #{}: block={} ({} bytes), header={} ({} bytes)",
                    blockNumber,
                    blockExists ? "✅" : "❌",
                    blockExists ? blockData.length : 0,
                    headerExists ? "✅" : "❌",
                    headerExists ? headerData.length : 0);

            if (blockExists) existingBlocks++;
            else missingBlocks++;

            // If block is missing, this could be the cause of the BlockFetch error
            if (!blockExists) {
                log.warn("⚠️  CRITICAL: Block #{} is missing - this could cause BlockFetch 'Block missing after availability check' errors", blockNumber);
            }
        }

        log.info("=== Block Existence Results ===");
        log.info("Existing blocks: {}/{}", existingBlocks, testBlockNumbers.size());
        log.info("Missing blocks: {}/{}", missingBlocks, testBlockNumbers.size());

        if (missingBlocks > 0) {
            log.warn("⚠️  Found {} missing blocks that could cause BlockFetch server errors", missingBlocks);
        }
    }
}
