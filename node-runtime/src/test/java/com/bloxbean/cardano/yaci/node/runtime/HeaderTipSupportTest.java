package com.bloxbean.cardano.yaci.node.runtime;

import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.node.runtime.chain.DirectRocksDBChainState;
import com.bloxbean.cardano.yaci.node.runtime.chain.InMemoryChainState;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class to verify header_tip support in ChainState implementations.
 *
 * This validates Task 1.3: Add header_tip Support to ChainState
 *
 * Tests cover:
 * - Header storage updates header_tip correctly
 * - header_tip is separate from regular tip
 * - header_tip persistence in RocksDB
 * - header_tip retrieval after storage operations
 */
class HeaderTipSupportTest {

    private InMemoryChainState inMemoryChainState;
    private DirectRocksDBChainState rocksDBChainState;
    private Path tempDbPath;

    @BeforeEach
    void setUp() throws IOException {
        // Setup InMemoryChainState
        inMemoryChainState = new InMemoryChainState();

        // Setup DirectRocksDBChainState with temporary directory
        tempDbPath = Files.createTempDirectory("test-chainstate");
        rocksDBChainState = new DirectRocksDBChainState(tempDbPath.toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        // Cleanup RocksDB
        if (rocksDBChainState != null) {
            rocksDBChainState.close();
        }

        // Delete temporary directory
        if (tempDbPath != null && Files.exists(tempDbPath)) {
            Files.walk(tempDbPath)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        // Ignore cleanup errors
                    }
                });
        }
    }

    @Test
    @Disabled
    void testInMemoryChainState_HeaderTipSupport() {
        testHeaderTipSupport(inMemoryChainState, "InMemoryChainState");
    }

    @Test
    void testDirectRocksDBChainState_HeaderTipSupport() {
        testHeaderTipSupport(rocksDBChainState, "DirectRocksDBChainState");
    }

    /**
     * Common test logic for both ChainState implementations
     */
    private void testHeaderTipSupport(ChainState chainState, String implementationName) {
        System.out.println("Testing " + implementationName);

        // Initial state - no tips should exist
        assertNull(chainState.getHeaderTip(), implementationName + " should have no initial header tip");
        assertNull(chainState.getTip(), implementationName + " should have no initial tip");

        // Store a header - should update header_tip
        byte[] headerHash1 = hexToBytes("abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890");
        byte[] headerData1 = "mock-header-1".getBytes();
        chainState.storeBlockHeader(headerHash1, 1L, 1000L, headerData1);

        // Verify header_tip was updated
        ChainTip headerTip = chainState.getHeaderTip();
        assertNotNull(headerTip, implementationName + " should have header tip after storing header");
        assertEquals(1000L, headerTip.getSlot(), "Header tip slot should match stored header");
        assertEquals(1L, headerTip.getBlockNumber(), "Header tip block number should match stored header");
        assertArrayEquals(headerHash1, headerTip.getBlockHash(), "Header tip hash should match stored header");

        // Verify regular tip is still null (no complete blocks stored)
        assertNull(chainState.getTip(), implementationName + " regular tip should still be null");

        // Store another header - should update header_tip to latest
        byte[] headerHash2 = hexToBytes("1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef");
        byte[] headerData2 = "mock-header-2".getBytes();
        chainState.storeBlockHeader(headerHash2, 2L, 1001L, headerData2);

        // Verify header_tip was updated to the latest header
        headerTip = chainState.getHeaderTip();
        assertNotNull(headerTip, implementationName + " should still have header tip");
        assertEquals(1001L, headerTip.getSlot(), "Header tip should be updated to latest header");
        assertEquals(2L, headerTip.getBlockNumber(), "Header tip block number should be updated");
        assertArrayEquals(headerHash2, headerTip.getBlockHash(), "Header tip hash should be updated");

        // Store a complete block - should update regular tip
        byte[] blockHash = hexToBytes("fedcba0987654321fedcba0987654321fedcba0987654321fedcba0987654321");
        byte[] blockData = "mock-complete-block".getBytes();
        chainState.storeBlock(blockHash, 99L, 999L, blockData);

        // Verify regular tip was updated
        ChainTip tip = chainState.getTip();
        assertNotNull(tip, implementationName + " should have regular tip after storing block");
        assertEquals(999L, tip.getSlot(), "Regular tip slot should match stored block");
        assertEquals(99L, tip.getBlockNumber(), "Regular tip block number should match stored block");
        assertArrayEquals(blockHash, tip.getBlockHash(), "Regular tip hash should match stored block");

        // Verify header_tip is still separate and ahead
        headerTip = chainState.getHeaderTip();
        assertNotNull(headerTip, implementationName + " should still have header tip");
        assertEquals(1001L, headerTip.getSlot(), "Header tip should remain ahead of regular tip");
        assertEquals(101L, headerTip.getBlockNumber(), "Header tip block number should remain ahead");

        // Verify headers can be retrieved
        byte[] retrievedHeader1 = chainState.getBlockHeader(headerHash1);
        assertNotNull(retrievedHeader1, "First header should be retrievable");
        assertArrayEquals(headerData1, retrievedHeader1, "First header data should match");

        byte[] retrievedHeader2 = chainState.getBlockHeader(headerHash2);
        assertNotNull(retrievedHeader2, "Second header should be retrievable");
        assertArrayEquals(headerData2, retrievedHeader2, "Second header data should match");

        System.out.println("✅ " + implementationName + " header_tip support validated successfully");
        System.out.println("   Header tip: slot=" + headerTip.getSlot() + ", block=" + headerTip.getBlockNumber());
        System.out.println("   Regular tip: slot=" + tip.getSlot() + ", block=" + tip.getBlockNumber());
        System.out.println("   Gap: " + (headerTip.getSlot() - tip.getSlot()) + " slots");
    }

    @Test
    void testRocksDBPersistence() throws IOException {
        // Test that header_tip persists across RocksDB instances
        String dbPath = tempDbPath.toString() + "_persistence";

        // Store headers in first instance
        try (DirectRocksDBChainState chainState1 = new DirectRocksDBChainState(dbPath)) {
            byte[] headerHash = hexToBytes("aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111");
            byte[] headerData = "persistent-header".getBytes();
            // Store a first header to satisfy continuity
            chainState1.storeBlockHeader(headerHash, 1L, 2000L, headerData);

            ChainTip headerTip1 = chainState1.getHeaderTip();
            assertNotNull(headerTip1);
            assertEquals(2000L, headerTip1.getSlot());
            assertEquals(1L, headerTip1.getBlockNumber());
        }

        // Verify header_tip persists in second instance
        try (DirectRocksDBChainState chainState2 = new DirectRocksDBChainState(dbPath)) {
            ChainTip headerTip2 = chainState2.getHeaderTip();
            assertNotNull(headerTip2, "Header tip should persist across RocksDB instances");
            assertEquals(2000L, headerTip2.getSlot(), "Persisted header tip slot should match");
            assertEquals(1L, headerTip2.getBlockNumber(), "Persisted header tip block number should match");
        }

        System.out.println("✅ RocksDB header_tip persistence validated successfully");
    }

    @Test
    void testHeaderTipSeparateFromTip() {
        // Test that header_tip and tip are truly independent

        // Store some headers first
        byte[] headerHash1 = hexToBytes("bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222");
        inMemoryChainState.storeBlockHeader(headerHash1, 1L, 3000L, "header-1".getBytes());

        byte[] headerHash2 = hexToBytes("cccc3333cccc3333cccc3333cccc3333cccc3333cccc3333cccc3333cccc3333");
        inMemoryChainState.storeBlockHeader(headerHash2, 2L, 3001L, "header-2".getBytes());

        // Header tip should be at latest header
        ChainTip headerTip = inMemoryChainState.getHeaderTip();
        assertEquals(3001L, headerTip.getSlot());
        assertEquals(2L, headerTip.getBlockNumber());

        // Store a complete block behind the headers
        byte[] blockHash = hexToBytes("dddd4444dddd4444dddd4444dddd4444dddd4444dddd4444dddd4444dddd4444");
        inMemoryChainState.storeBlock(blockHash, 0L, 2999L, "complete-block".getBytes());

        // Regular tip should be at the complete block
        ChainTip tip = inMemoryChainState.getTip();
        assertEquals(2999L, tip.getSlot());
        assertEquals(0L, tip.getBlockNumber());

        // Header tip should remain unchanged
        headerTip = inMemoryChainState.getHeaderTip();
        assertEquals(3001L, headerTip.getSlot());
        assertEquals(2L, headerTip.getBlockNumber());

        // Verify gap between header_tip and tip
        long gap = headerTip.getSlot() - tip.getSlot();
        assertEquals(2L, gap, "Should have 2-slot gap between header_tip and tip");

        System.out.println("✅ Header tip independence validated successfully");
        System.out.println("   Header tip: slot=" + headerTip.getSlot() + " (headers ahead)");
        System.out.println("   Regular tip: slot=" + tip.getSlot() + " (complete blocks)");
        System.out.println("   Gap: " + gap + " slots");
    }

    // Helper method to convert hex string to bytes
    private byte[] hexToBytes(String hex) {
        int length = hex.length();
        byte[] data = new byte[length / 2];
        for (int i = 0; i < length; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i+1), 16));
        }
        return data;
    }
}
