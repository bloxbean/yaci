 package com.bloxbean.cardano.yaci.node.runtime;

import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.node.runtime.chain.DirectRocksDBChainState;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.ArrayList;
import java.util.List;

/**
 * Test to analyze header gaps in the chain state.
 * This test helps identify gaps in the header chain that might cause
 * issues with block body fetching after rollbacks.
 */
@Slf4j
public class HeaderGapAnalysisTest {

    /**
     * Only run this test if there's an existing RocksDB chainstate directory.
     * This test is designed to analyze real chain data, not create test data.
     */
    @Test
    public void testAnalyzeHeaderGaps() {
        String chainStatePath = "/Users/satya/work/bloxbean/yaci/chainstate";

        // Check if chainstate directory exists
        if (!hasChainStateDirectory(chainStatePath)) {
            log.warn("⚠️ Chainstate directory not found at {} - skipping test", chainStatePath);
            return;
        }

        try (DirectRocksDBChainState chainState = new DirectRocksDBChainState(chainStatePath)) {
            log.info("🔍 Starting header gap analysis for chainstate at: {}", chainStatePath);

            ChainTip tip = chainState.getTip();
            ChainTip headerTip = chainState.getHeaderTip();

            log.info("📊 Chain state summary:");
            log.info("   Body tip: {}", tip != null ?
                    String.format("block #%d at slot %d", tip.getBlockNumber(), tip.getSlot()) : "null");
            log.info("   Header tip: {}", headerTip != null ?
                    String.format("block #%d at slot %d", headerTip.getBlockNumber(), headerTip.getSlot()) : "null");

            if (tip == null && headerTip == null) {
                log.warn("⚠️ No data found in chainstate - skipping analysis");
                return;
            }

            // Analyze gaps in headers
            analyzeHeaderGaps(chainState, tip, headerTip);

            // Analyze gaps in block bodies
            analyzeBodyGaps(chainState, tip, headerTip);

            log.info("✅ Header gap analysis completed");

        } catch (Exception e) {
            log.error("Failed to analyze header gaps", e);
            throw new RuntimeException("Header gap analysis failed", e);
        }
    }

    /**
     * Analyze gaps in the header chain by checking sequential block numbers.
     */
    private void analyzeHeaderGaps(ChainState chainState, ChainTip tip, ChainTip headerTip) {
        if (headerTip == null) {
            log.warn("⚠️ No header tip - cannot analyze header gaps");
            return;
        }

        log.info("🔍 Analyzing header gaps from block 1 to block {}...", headerTip.getBlockNumber());

        List<Long> missingHeaders = new ArrayList<>();
        List<HeaderGap> gaps = new ArrayList<>();

        long startBlock = 1;
        long endBlock = Math.min(headerTip.getBlockNumber(), 150000); // Limit to first 150k blocks for performance

        long gapStart = -1;
        int consecutiveMissing = 0;

        for (long blockNumber = startBlock; blockNumber <= endBlock; blockNumber++) {
            byte[] header = chainState.getBlockHeaderByNumber(blockNumber);

            if (header == null) {
                if (gapStart == -1) {
                    gapStart = blockNumber;
                }
                missingHeaders.add(blockNumber);
                consecutiveMissing++;
            } else {
                if (gapStart != -1) {
                    // End of a gap
                    gaps.add(new HeaderGap(gapStart, blockNumber - 1, consecutiveMissing));
                    gapStart = -1;
                    consecutiveMissing = 0;
                }
            }

            // Progress logging every 10k blocks
            if (blockNumber % 10000 == 0) {
                log.info("   Analyzed up to block #{} - found {} gaps so far", blockNumber, gaps.size());
            }
        }

        // Handle gap at the end
        if (gapStart != -1) {
            gaps.add(new HeaderGap(gapStart, endBlock, consecutiveMissing));
        }

        // Report results
        log.info("📋 Header gap analysis results:");
        log.info("   Total missing headers: {}", missingHeaders.size());
        log.info("   Number of gaps: {}", gaps.size());

        if (!gaps.isEmpty()) {
            log.warn("⚠️ Found {} header gaps:", gaps.size());

            // Show first 10 gaps in detail
            int maxGapsToShow = Math.min(10, gaps.size());
            for (int i = 0; i < maxGapsToShow; i++) {
                HeaderGap gap = gaps.get(i);
                log.warn("   Gap #{}: blocks {} to {} ({} missing blocks)",
                        i + 1, gap.startBlock, gap.endBlock, gap.size);
            }

            if (gaps.size() > maxGapsToShow) {
                log.warn("   ... and {} more gaps", gaps.size() - maxGapsToShow);
            }

            // Find the largest gap
            HeaderGap largestGap = gaps.stream()
                    .max((g1, g2) -> Integer.compare(g1.size, g2.size))
                    .orElse(null);

            if (largestGap != null) {
                log.warn("🚨 Largest gap: blocks {} to {} ({} missing blocks)",
                        largestGap.startBlock, largestGap.endBlock, largestGap.size);
            }
        } else {
            log.info("✅ No header gaps found - headers are sequential");
        }
    }

    /**
     * Analyze gaps in the block body chain.
     */
    private void analyzeBodyGaps(ChainState chainState, ChainTip tip, ChainTip headerTip) {
        if (tip == null) {
            log.warn("⚠️ No body tip - cannot analyze body gaps");
            return;
        }

        log.info("🔍 Analyzing body gaps from block 1 to block {}...", tip.getBlockNumber());

        List<Long> missingBodies = new ArrayList<>();
        List<HeaderGap> gaps = new ArrayList<>();

        long startBlock = 1;
        long endBlock = Math.min(tip.getBlockNumber(), 150000); // Limit for performance

        long gapStart = -1;
        int consecutiveMissing = 0;

        for (long blockNumber = startBlock; blockNumber <= endBlock; blockNumber++) {
            byte[] body = chainState.getBlockByNumber(blockNumber);

            if (body == null) {
                if (gapStart == -1) {
                    gapStart = blockNumber;
                }
                missingBodies.add(blockNumber);
                consecutiveMissing++;
            } else {
                if (gapStart != -1) {
                    // End of a gap
                    gaps.add(new HeaderGap(gapStart, blockNumber - 1, consecutiveMissing));
                    gapStart = -1;
                    consecutiveMissing = 0;
                }
            }

            // Progress logging every 10k blocks
            if (blockNumber % 10000 == 0) {
                log.info("   Analyzed up to block #{} - found {} gaps so far", blockNumber, gaps.size());
            }
        }

        // Handle gap at the end
        if (gapStart != -1) {
            gaps.add(new HeaderGap(gapStart, endBlock, consecutiveMissing));
        }

        // Report results
        log.info("📋 Body gap analysis results:");
        log.info("   Total missing bodies: {}", missingBodies.size());
        log.info("   Number of gaps: {}", gaps.size());

        if (!gaps.isEmpty()) {
            log.warn("⚠️ Found {} body gaps:", gaps.size());

            // Show first 10 gaps in detail
            int maxGapsToShow = Math.min(10, gaps.size());
            for (int i = 0; i < maxGapsToShow; i++) {
                HeaderGap gap = gaps.get(i);
                log.warn("   Gap #{}: blocks {} to {} ({} missing blocks)",
                        i + 1, gap.startBlock, gap.endBlock, gap.size);
            }

            if (gaps.size() > maxGapsToShow) {
                log.warn("   ... and {} more gaps", gaps.size() - maxGapsToShow);
            }
        } else {
            log.info("✅ No body gaps found - bodies are sequential");
        }
    }

    /**
     * Check if chainstate directory exists to conditionally enable the test.
     */
    static boolean hasChainStateDirectory(String chainStatePath) {
        return java.nio.file.Files.exists(java.nio.file.Paths.get(chainStatePath));
    }

    /**
     * Represents a gap in the chain (either headers or bodies).
     */
    private static class HeaderGap {
        final long startBlock;
        final long endBlock;
        final int size;

        HeaderGap(long startBlock, long endBlock, int size) {
            this.startBlock = startBlock;
            this.endBlock = endBlock;
            this.size = size;
        }
    }
}
