package com.bloxbean.cardano.yaci.node.runtime.utxo;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import com.bloxbean.cardano.yaci.events.impl.SimpleEventBus;
import com.bloxbean.cardano.yaci.node.runtime.chain.DirectRocksDBChainState;
import com.bloxbean.cardano.yaci.node.runtime.db.UtxoCfNames;
import com.bloxbean.cardano.yaci.node.runtime.events.BlockAppliedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rocksdb.ColumnFamilyHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.bloxbean.cardano.yaci.core.util.Constants.LOVELACE;
import static org.junit.jupiter.api.Assertions.*;

class MmrUtxoStoreTest {
    private File tempDir;
    private DirectRocksDBChainState chain;
    private MmrUtxoStore store;
    private EventBus bus;

    @BeforeEach
    void setup() throws Exception {
        tempDir = Files.createTempDirectory("yaci-utxo-mmr-test").toFile();
        chain = new DirectRocksDBChainState(tempDir.getAbsolutePath());
        bus = new SimpleEventBus();
        Logger log = LoggerFactory.getLogger(MmrUtxoStoreTest.class);
        Map<String, Object> cfg = new java.util.HashMap<>();
        cfg.put("yaci.node.utxo.enabled", true);
        cfg.put("yaci.node.utxo.pruneDepth", 3);
        cfg.put("yaci.node.utxo.rollbackWindow", 4);
        cfg.put("yaci.node.utxo.pruneBatchSize", 100);
        store = new MmrUtxoStore(chain, log, cfg);
        new UtxoEventHandler(bus, store);
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
                .unit(LOVELACE)
                .quantity(new BigInteger(String.valueOf(v)))
                .build();
    }

    @Test
    void storeType_and_mmrNodeAppend_onApply() throws Exception {
        assertTrue(store.isEnabled());
        assertEquals("mmr", store.storeType());

        String addr = "addr_test1vpxmmr00000000000000000000000000000000000000";
        TransactionBody tx = TransactionBody.builder()
                .txHash("aa".repeat(32))
                .outputs(List.of(TransactionOutput.builder().address(addr)
                        .amounts(List.of(lovelaceAmount(123))).build()))
                .build();
        Block block = Block.builder()
                .era(Era.Babbage)
                .transactionBodies(List.of(tx))
                .invalidTransactions(Collections.emptyList())
                .build();

        long slot = 10L;
        long blockNo = 1L;
        String hash = "bb".repeat(32);
        bus.publish(new BlockAppliedEvent(Era.Babbage, slot, blockNo, hash, block),
                EventMetadata.builder().origin("test").slot(slot).blockNo(blockNo).blockHash(hash).build(),
                PublishOptions.builder().build());

        // Allow apply to run synchronously via handler; verify lastApplied and MMR node exists
        assertEquals(1L, store.getLastAppliedBlock());

        ColumnFamilyHandle cf = chain.rocks().handle(UtxoCfNames.UTXO_MMR_NODES);
        byte[] key = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(blockNo).array();
        byte[] payload = chain.rocks().db().get(cf, key);
        assertNotNull(payload, "MMR node should be appended for block 1");
        assertEquals(8 + 8 + 4 + 4, payload.length);
    }
}

