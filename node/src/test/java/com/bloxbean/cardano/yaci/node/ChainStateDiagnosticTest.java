package com.bloxbean.cardano.yaci.node;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.node.chain.DirectRocksDBChainState;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Diagnostic test to understand chainstate issues
 */
@Slf4j
public class ChainStateDiagnosticTest {

    @Test
    void diagnoseChainState() {
        String chainstatePath = "/Users/satya/work/bloxbean/yaci/chainstate";
        log.info("=== ChainState Diagnostic Analysis ===");

        DirectRocksDBChainState chainState = new DirectRocksDBChainState(chainstatePath);

        try {
            // 1. Check tip
            ChainTip tip = chainState.getTip();
            if (tip != null) {
                log.info("Current tip: block={}, slot={}, hash={}",
                    tip.getBlockNumber(), tip.getSlot(), HexUtil.encodeHexString(tip.getBlockHash()));
            } else {
                log.error("No tip found!");
            }

            // 2. Check first block
            Point firstBlock = chainState.getFirstBlock();
            if (firstBlock != null) {
                log.info("First block: slot={}, hash={}", firstBlock.getSlot(), firstBlock.getHash());
            } else {
                log.error("No first block found!");
            }

            // 3. Try to find any blocks in storage by checking various slots
            log.info("\n=== Searching for blocks at various slots ===");
            List<Long> testSlots = List.of(0L, 1L, 100L, 1000L, 10000L, 100000L, 1000000L);

            for (Long slot : testSlots) {
                try {
                    // Try to find blocks near this slot
                    log.info("Checking around slot {}...", slot);

                    // Check if we can get block hash at slot (this might not work directly)
                    // But let's see what's available
                } catch (Exception e) {
                    log.debug("No data at slot {}: {}", slot, e.getMessage());
                }
            }

            // 4. Check RocksDB internal state
            log.info("\n=== RocksDB Internal State ===");
            try {
                // The issue might be that tip is not being updated properly
                // Let's see if we can manually scan for blocks

                // Try to read some blocks by hash if we know any
                byte[] genesisHash = HexUtil.decodeHexString("9ad7ff320c9cf74e0f5ee78d22a85ce42bb0a487d0506bf60cfb5a91ea4497d2");
                byte[] genesisBlock = chainState.getBlock(genesisHash);
                if (genesisBlock != null) {
                    log.info("Genesis block found: {} bytes", genesisBlock.length);
                }

                byte[] genesisHeader = chainState.getBlockHeader(genesisHash);
                if (genesisHeader != null) {
                    log.info("Genesis header found: {} bytes", genesisHeader.length);
                }

            } catch (Exception e) {
                log.error("Error checking RocksDB state", e);
            }

            // 5. Check if this is a sync issue
            log.info("\n=== Sync State Analysis ===");
            log.info("If tip is at slot 0 but RocksDB has 196MB of data, possible causes:");
            log.info("1. Blocks are being stored but tip is not being updated");
            log.info("2. Data is being written but not committed properly");
            log.info("3. Multiple sync attempts creating orphaned data");
            log.info("4. Header-only sync without updating tip");

        } finally {
            chainState.close();
        }

        log.info("\n=== Diagnostic complete ===");
    }

    @Test
    void testChainStateRecovery() {
        log.info("=== Testing ChainState Recovery ===");

        // This test checks if we can recover from a stuck chainstate
        String chainstatePath = "/Users/satya/work/bloxbean/yaci/chainstate";
        DirectRocksDBChainState chainState = new DirectRocksDBChainState(chainstatePath);

        try {
            ChainTip currentTip = chainState.getTip();
            log.info("Current tip before recovery: {}", currentTip);

            // Check if we need to clear and restart
            if (currentTip != null && currentTip.getSlot() == 0 && currentTip.getBlockNumber() == 0) {
                log.warn("ChainState appears stuck at genesis despite having data");
                log.warn("Recommendation: Clear chainstate and restart sync");
                log.warn("Commands to fix:");
                log.warn("1. Stop the node");
                log.warn("2. rm -rf /Users/satya/work/bloxbean/yaci/chainstate");
                log.warn("3. Restart the node for fresh sync");
            }

        } finally {
            chainState.close();
        }
    }
}
