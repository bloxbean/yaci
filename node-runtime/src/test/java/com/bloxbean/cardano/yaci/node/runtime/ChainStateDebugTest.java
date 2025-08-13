package com.bloxbean.cardano.yaci.node.runtime;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.node.runtime.chain.DirectRocksDBChainState;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@Slf4j
public class ChainStateDebugTest {

    private ChainState chainState;

    @BeforeEach
    void setUp() {
        // Use the relative path that works from the project root
        String chainStatePath = "./chainstate";
        try {
            chainState = new DirectRocksDBChainState(chainStatePath);
        } catch (Exception e) {
            log.error("Failed to initialize chainstate at {}: {}", chainStatePath, e.getMessage());
            // Try absolute path as fallback
            chainStatePath = "/Users/satya/work/bloxbean/yaci/chainstate";
            chainState = new DirectRocksDBChainState(chainStatePath);
        }
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

    @Test
    void debugBlockNumberContinuity() {
        log.info("=== Debugging Block Number Continuity ===");
        
        ChainTip tip = chainState.getTip();
        ChainTip headerTip = chainState.getHeaderTip();
        Point firstBlock = chainState.getFirstBlock();
        
        log.info("Tip: slot={}, blockNumber={}", tip.getSlot(), tip.getBlockNumber());
        log.info("Header Tip: slot={}, blockNumber={}", headerTip.getSlot(), headerTip.getBlockNumber());
        log.info("First Block: slot={}, hash={}", firstBlock.getSlot(), firstBlock.getHash());
        
        // Test first 20 blocks to see the pattern
        log.info("=== Testing First 20 Blocks ===");
        for (long blockNum = 1; blockNum <= 20; blockNum++) {
            byte[] blockData = chainState.getBlockByNumber(blockNum);
            byte[] headerData = chainState.getBlockHeaderByNumber(blockNum);
            
            log.info("Block #{}: body={} ({} bytes), header={} ({} bytes)",
                    blockNum,
                    blockData != null ? "✅" : "❌",
                    blockData != null ? blockData.length : 0,
                    headerData != null ? "✅" : "❌", 
                    headerData != null ? headerData.length : 0);
        }
        
        // Test around the middle
        long midPoint = tip.getBlockNumber() / 2;
        log.info("=== Testing Around Middle Block {} ===", midPoint);
        for (long blockNum = midPoint - 10; blockNum <= midPoint + 10; blockNum++) {
            byte[] blockData = chainState.getBlockByNumber(blockNum);
            byte[] headerData = chainState.getBlockHeaderByNumber(blockNum);
            
            log.info("Block #{}: body={} ({} bytes), header={} ({} bytes)",
                    blockNum,
                    blockData != null ? "✅" : "❌",
                    blockData != null ? blockData.length : 0,
                    headerData != null ? "✅" : "❌", 
                    headerData != null ? headerData.length : 0);
        }
        
        // Test the end
        log.info("=== Testing Last 20 Blocks ===");
        for (long blockNum = tip.getBlockNumber() - 19; blockNum <= tip.getBlockNumber(); blockNum++) {
            byte[] blockData = chainState.getBlockByNumber(blockNum);
            byte[] headerData = chainState.getBlockHeaderByNumber(blockNum);
            
            log.info("Block #{}: body={} ({} bytes), header={} ({} bytes)",
                    blockNum,
                    blockData != null ? "✅" : "❌",
                    blockData != null ? blockData.length : 0,
                    headerData != null ? "✅" : "❌", 
                    headerData != null ? headerData.length : 0);
        }
        
        // Test specific problematic blocks from original test
        log.info("=== Testing Specific Problematic Blocks ===");
        long[] problematicBlocks = {87956, 87957, 87958, 87959, 87960};
        for (long blockNum : problematicBlocks) {
            byte[] blockData = chainState.getBlockByNumber(blockNum);
            byte[] headerData = chainState.getBlockHeaderByNumber(blockNum);
            
            log.warn("Block #{}: body={} ({} bytes), header={} ({} bytes)",
                    blockNum,
                    blockData != null ? "✅" : "❌",
                    blockData != null ? blockData.length : 0,
                    headerData != null ? "✅" : "❌", 
                    headerData != null ? headerData.length : 0);
        }
    }
    
    @Test
    void debugActualGapCount() {
        log.info("=== Debugging Actual Gap Count ===");
        
        ChainTip tip = chainState.getTip();
        
        int sampleSize = 1000;
        int missingCount = 0;
        
        // Sample every 1000th block to get a representative view
        log.info("Sampling every 1000th block from 1 to {}", tip.getBlockNumber());
        
        for (long blockNum = 1; blockNum <= tip.getBlockNumber(); blockNum += 1000) {
            byte[] blockData = chainState.getBlockByNumber(blockNum);
            if (blockData == null) {
                missingCount++;
                log.warn("Missing block at #{}", blockNum);
            }
        }
        
        double totalSampled = Math.ceil((double) tip.getBlockNumber() / 1000);
        double missingPercentage = (double) missingCount / totalSampled * 100;
        
        log.info("Sample Results: {}/{} blocks missing ({:.2f}%)", 
                missingCount, (int)totalSampled, missingPercentage);
        
        if (missingPercentage > 10) {
            log.error("🚨 CRITICAL: {}% of blocks are missing - chainstate is severely corrupted", missingPercentage);
        } else if (missingPercentage > 0) {
            log.warn("⚠️  WARNING: {}% of blocks are missing", missingPercentage);
        } else {
            log.info("✅ No missing blocks detected in sample");
        }
    }
    
    @Test 
    void debugRocksDBDirectAccess() {
        log.info("=== Debugging RocksDB Direct Access ===");
        
        // Test if the issue is in our ChainState implementation
        DirectRocksDBChainState rocksDB = (DirectRocksDBChainState) chainState;
        
        // Check a few blocks by different access methods
        for (long blockNum = 1; blockNum <= 5; blockNum++) {
            byte[] blockByNumber = chainState.getBlockByNumber(blockNum);
            byte[] headerByNumber = chainState.getBlockHeaderByNumber(blockNum);
            
            log.info("Block #{}: getBlockByNumber={}, getBlockHeaderByNumber={}",
                    blockNum,
                    blockByNumber != null ? "✅ " + blockByNumber.length + " bytes" : "❌ null",
                    headerByNumber != null ? "✅ " + headerByNumber.length + " bytes" : "❌ null");
        }
    }
}