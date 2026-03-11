package com.bloxbean.cardano.yaci.node.runtime.chain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class BlockPrunerTest {

    private File tempDir;
    private DirectRocksDBChainState chain;

    @BeforeEach
    void setup() throws Exception {
        tempDir = Files.createTempDirectory("yaci-pruner-test").toFile();
        chain = new DirectRocksDBChainState(tempDir.getAbsolutePath());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (chain != null) chain.close();
        if (tempDir != null) deleteRecursively(tempDir);
    }

    private void deleteRecursively(File f) {
        if (f.isDirectory()) {
            File[] files = f.listFiles();
            if (files != null) for (File c : files) deleteRecursively(c);
        }
        f.delete();
    }

    private byte[] hash(int i) {
        byte[] h = new byte[32];
        h[0] = (byte) (i >> 8);
        h[1] = (byte) (i & 0xff);
        return h;
    }

    private byte[] headerBytes(int i) {
        return ("header-" + i).getBytes();
    }

    private byte[] blockBody(int i) {
        return ("block-body-" + i).getBytes();
    }

    /**
     * Store a header + block body using forceStore methods to bypass continuity checks.
     */
    private void storeBlockAndHeader(int blockNum, int slot) throws Exception {
        byte[] h = hash(blockNum);
        // Use forceStore to bypass continuity checks in test
        chain.forceStoreBlockHeader(h, (long) blockNum, (long) slot, headerBytes(blockNum));
        chain.forceStoreBlock(h, (long) blockNum, (long) slot, blockBody(blockNum));
    }

    @Test
    void pruneOnce_deletesOldBlockBodies() throws Exception {
        // Store 10 blocks (0-9)
        for (int i = 0; i < 10; i++) {
            storeBlockAndHeader(i, i * 10);
        }

        // Verify all block bodies exist
        for (int i = 0; i < 10; i++) {
            assertNotNull(chain.getBlock(hash(i)), "Block " + i + " should exist before pruning");
        }

        // Create pruner: retain last 3 blocks, batch size 100
        BlockPruner pruner = new BlockPruner(chain, 3, 100);
        pruner.pruneOnce();

        // Blocks 0-6 should be pruned (tip=9, cutoff=9-3=6)
        for (int i = 0; i <= 6; i++) {
            assertNull(chain.getBlock(hash(i)), "Block " + i + " should be pruned");
        }

        // Blocks 7-9 should still exist
        for (int i = 7; i <= 9; i++) {
            assertNotNull(chain.getBlock(hash(i)), "Block " + i + " should be retained");
        }

        // Headers should still exist for ALL blocks
        for (int i = 0; i < 10; i++) {
            assertNotNull(chain.getBlockHeader(hash(i)), "Header " + i + " should be preserved");
        }
    }

    @Test
    void pruneOnce_respectsBatchSize() throws Exception {
        // Store 10 blocks (0-9)
        for (int i = 0; i < 10; i++) {
            storeBlockAndHeader(i, i * 10);
        }

        // Create pruner: retain 2 blocks, batch=3
        BlockPruner pruner = new BlockPruner(chain, 2, 3);

        // First pass: should prune at most 3 blocks (blocks 0, 1, 2)
        pruner.pruneOnce();

        // Block 0, 1, 2 should be pruned
        assertNull(chain.getBlock(hash(0)));
        assertNull(chain.getBlock(hash(1)));
        assertNull(chain.getBlock(hash(2)));

        // Block 3 should still exist (not yet reached by cursor)
        assertNotNull(chain.getBlock(hash(3)));

        // Second pass
        pruner.pruneOnce();
        assertNull(chain.getBlock(hash(3)));
        assertNull(chain.getBlock(hash(4)));
        assertNull(chain.getBlock(hash(5)));

        // Block 6 should still exist
        assertNotNull(chain.getBlock(hash(6)));
    }

    @Test
    void pruneOnce_noOp_whenNotEnoughBlocks() throws Exception {
        // Store only 2 blocks, retention=5
        storeBlockAndHeader(0, 0);
        storeBlockAndHeader(1, 10);

        BlockPruner pruner = new BlockPruner(chain, 5, 100);
        pruner.pruneOnce(); // should be no-op

        assertNotNull(chain.getBlock(hash(0)));
        assertNotNull(chain.getBlock(hash(1)));
    }

    @Test
    void pruneOnce_idempotent_secondCallNoOp() throws Exception {
        for (int i = 0; i < 5; i++) {
            storeBlockAndHeader(i, i * 10);
        }

        BlockPruner pruner = new BlockPruner(chain, 2, 100);
        pruner.pruneOnce(); // prune blocks 0, 1, 2

        assertNull(chain.getBlock(hash(0)));
        assertNull(chain.getBlock(hash(1)));
        assertNull(chain.getBlock(hash(2)));
        assertNotNull(chain.getBlock(hash(3)));

        // Second call should be no-op (cursor already at cutoff)
        pruner.pruneOnce();
        assertNotNull(chain.getBlock(hash(3)));
        assertNotNull(chain.getBlock(hash(4)));
    }

    @Test
    void pruneOnce_resumesFromCursor_afterRestart() throws Exception {
        for (int i = 0; i < 10; i++) {
            storeBlockAndHeader(i, i * 10);
        }

        // First pruner instance: prune with batch=3
        BlockPruner pruner1 = new BlockPruner(chain, 2, 3);
        pruner1.pruneOnce(); // prunes blocks 0, 1, 2

        // Simulate restart: create new pruner instance
        BlockPruner pruner2 = new BlockPruner(chain, 2, 3);
        pruner2.pruneOnce(); // should resume from cursor, prune 3, 4, 5

        assertNull(chain.getBlock(hash(3)));
        assertNull(chain.getBlock(hash(4)));
        assertNull(chain.getBlock(hash(5)));

        // Block 6 still around
        assertNotNull(chain.getBlock(hash(6)));
    }
}
