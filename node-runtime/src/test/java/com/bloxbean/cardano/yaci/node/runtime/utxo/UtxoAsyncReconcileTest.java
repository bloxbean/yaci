package com.bloxbean.cardano.yaci.node.runtime.utxo;

import com.bloxbean.cardano.yaci.core.model.*;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.impl.SimpleEventBus;
import com.bloxbean.cardano.yaci.node.runtime.chain.DirectRocksDBChainState;
import com.bloxbean.cardano.yaci.node.runtime.events.BlockAppliedEvent;
import com.bloxbean.cardano.yaci.node.runtime.events.RollbackEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;

import static com.bloxbean.cardano.yaci.core.util.Constants.LOVELACE;
import static org.junit.jupiter.api.Assertions.*;

class UtxoAsyncReconcileTest {
    private File tempDir;
    private DirectRocksDBChainState chain;
    private ClassicUtxoStore store;

    @BeforeEach
    void setup() throws Exception {
        tempDir = Files.createTempDirectory("yaci-utxo-async-test").toFile();
        chain = new DirectRocksDBChainState(tempDir.getAbsolutePath());
        Logger log = LoggerFactory.getLogger(UtxoAsyncReconcileTest.class);
        java.util.Map<String, Object> cfg = new java.util.HashMap<>();
        cfg.put("yaci.node.utxo.enabled", true);
        cfg.put("yaci.node.utxo.pruneDepth", 3);
        cfg.put("yaci.node.utxo.rollbackWindow", 4);
        cfg.put("yaci.node.utxo.pruneBatchSize", 100);
        store = new ClassicUtxoStore(chain, log, cfg);
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

    private static com.bloxbean.cardano.yaci.core.model.Amount lovelaceAmount(long v) {
        return com.bloxbean.cardano.yaci.core.model.Amount.builder()
                .policyId(LOVELACE)
                .quantity(new BigInteger(String.valueOf(v)))
                .build();
    }

    @Test
    void asyncApply_preservesOrdering() throws Exception {
        EventBus bus = new SimpleEventBus();
        try (UtxoEventHandlerAsync handler = new UtxoEventHandlerAsync(bus, store)) {
            // Seed block creates a UTXO
            String addr = "addr_test1vpxasync000000000000000000000000000000000000";
            TransactionBody tx1 = TransactionBody.builder()
                    .txHash("a1".repeat(32))
                    .outputs(List.of(TransactionOutput.builder().address(addr).amounts(List.of(lovelaceAmount(100))).build()))
                    .build();
            Block b1 = Block.builder().era(Era.Babbage).transactionBodies(List.of(tx1)).invalidTransactions(Collections.emptyList()).build();

            // Next block spends it
            TransactionBody tx2 = TransactionBody.builder()
                    .txHash("a2".repeat(32))
                    .inputs(java.util.Set.of(TransactionInput.builder().transactionId(tx1.getTxHash()).index(0).build()))
                    .outputs(List.of())
                    .build();
            Block b2 = Block.builder().era(Era.Babbage).transactionBodies(List.of(tx2)).invalidTransactions(Collections.emptyList()).build();

            // Publish quickly; processing will happen on single worker
            bus.publish(new BlockAppliedEvent(Era.Babbage, 10, 1, "h1".repeat(32), b1),
                    EventMetadata.builder().origin("test").slot(10).blockNo(1).blockHash("h1".repeat(32)).build(), PublishOptions.builder().build());
            bus.publish(new BlockAppliedEvent(Era.Babbage, 11, 2, "h2".repeat(32), b2),
                    EventMetadata.builder().origin("test").slot(11).blockNo(2).blockHash("h2".repeat(32)).build(), PublishOptions.builder().build());

            // Wait until lastAppliedBlock reaches 2
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline && store.readLastAppliedBlock() < 2) {
                Thread.sleep(10);
            }
            assertEquals(2, store.readLastAppliedBlock(), "async apply should reach block 2");
            assertTrue(store.getUtxosByAddress(addr, 1, 10).isEmpty(), "spent UTXO should be gone");
        }
    }

    @Test
    void reconcile_forward_then_rollback_handles_crash_scenarios() throws Exception {
        // Build two blocks (b1 creates, b2 spends)
        String addr = "addr_test1vpxrecon000000000000000000000000000000000000";
        TransactionBody tx1 = TransactionBody.builder()
                .txHash("b1".repeat(32))
                .outputs(List.of(TransactionOutput.builder().address(addr).amounts(List.of(lovelaceAmount(50))).build()))
                .build();
        Block b1 = Block.builder().era(Era.Babbage).transactionBodies(List.of(tx1)).invalidTransactions(Collections.emptyList()).build();

        TransactionBody tx2 = TransactionBody.builder()
                .txHash("b2".repeat(32))
                .inputs(java.util.Set.of(TransactionInput.builder().transactionId(tx1.getTxHash()).index(0).build()))
                .outputs(List.of())
                .build();
        Block b2 = Block.builder().era(Era.Babbage).transactionBodies(List.of(tx2)).invalidTransactions(Collections.emptyList()).build();

        // Store headers to chainstate for rollback targeting
        byte[] h1hash = com.bloxbean.cardano.yaci.core.util.HexUtil.decodeHexString("c1".repeat(32));
        byte[] h2hash = com.bloxbean.cardano.yaci.core.util.HexUtil.decodeHexString("c2".repeat(32));
        chain.storeBlockHeader(h1hash, 1L, 100L, "H1".getBytes(StandardCharsets.UTF_8));
        chain.storeBlock(h1hash, 1L, 100L, BlockSerializer.INSTANCE.serialize(b1));
        chain.storeBlockHeader(h2hash, 2L, 101L, "H2".getBytes(StandardCharsets.UTF_8));
        chain.storeBlock(h2hash, 2L, 101L, BlockSerializer.INSTANCE.serialize(b2));

        // Apply both blocks via synchronous handler to set UTXO ahead of tip
        EventBus bus = new SimpleEventBus();
        try (UtxoEventHandler handler = new UtxoEventHandler(bus, store)) {
            bus.publish(new BlockAppliedEvent(Era.Babbage, 100L, 1L, "c1".repeat(32), b1),
                    EventMetadata.builder().origin("test").slot(100L).blockNo(1L).blockHash("c1".repeat(32)).build(), PublishOptions.builder().build());
            bus.publish(new BlockAppliedEvent(Era.Babbage, 101L, 2L, "c2".repeat(32), b2),
                    EventMetadata.builder().origin("test").slot(101L).blockNo(2L).blockHash("c2".repeat(32)).build(), PublishOptions.builder().build());
        }
        assertEquals(2, store.readLastAppliedBlock());
        assertTrue(store.getUtxosByAddress(addr, 1, 10).isEmpty(), "UTXO should be spent after block #2");

        // Now simulate a chain rollback to slot 100 (back before block #2)
        chain.rollbackTo(100L);

        // Reconcile should detect lastApplied ahead of tip and rollback using deltas
        store.reconcile(chain);

        var list = store.getUtxosByAddress(addr, 1, 10);
        assertEquals(1, list.size());
        assertEquals(new BigInteger("50"), list.get(0).lovelace());
    }
}
