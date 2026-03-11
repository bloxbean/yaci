package com.bloxbean.cardano.yaci.node.runtime.utxo;

import com.bloxbean.cardano.yaci.core.model.*;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import com.bloxbean.cardano.yaci.events.impl.SimpleEventBus;
import com.bloxbean.cardano.yaci.node.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yaci.node.runtime.chain.DirectRocksDBChainState;
import com.bloxbean.cardano.yaci.node.api.events.BlockAppliedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.math.BigInteger;
import java.nio.file.Files;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class UtxoFilterIntegrationTest {

    private File tempDir;
    private DirectRocksDBChainState chain;
    private DefaultUtxoStore store;
    private EventBus bus;

    @BeforeEach
    void setup() throws Exception {
        tempDir = Files.createTempDirectory("yaci-filter-test").toFile();
        chain = new DirectRocksDBChainState(tempDir.getAbsolutePath());
        bus = new SimpleEventBus();
        Logger log = LoggerFactory.getLogger(UtxoFilterIntegrationTest.class);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("yaci.node.utxo.enabled", true);
        cfg.put("yaci.node.utxo.pruneDepth", 3);
        cfg.put("yaci.node.utxo.rollbackWindow", 4);
        cfg.put("yaci.node.utxo.pruneBatchSize", 100);
        store = new DefaultUtxoStore(chain, log, cfg);
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

    private Amount lovelaceAmount(long amount) {
        return Amount.builder().unit("lovelace").quantity(BigInteger.valueOf(amount)).build();
    }

    private void publishBlock(long slot, long blockNo, String hash, Block block) {
        bus.publish(new BlockAppliedEvent(Era.Babbage, slot, blockNo, hash, block),
                EventMetadata.builder().origin("test").slot(slot).blockNo(blockNo).blockHash(hash).build(),
                PublishOptions.builder().build());
    }

    @Test
    void filterByAddress_onlyMatchingUtxosStored() {
        String allowedAddr = "addr_test1vpxallowed00000000000000000000000000000000000";
        String blockedAddr = "addr_test1vpxblocked00000000000000000000000000000000000";

        // Configure filter to only allow one address
        var filter = new AddressUtxoFilter(Set.of(allowedAddr), Set.of());
        store.setFilterChain(new StorageFilterChain(List.of(filter)));

        // Create block with outputs to both addresses
        TransactionBody tx = TransactionBody.builder()
                .txHash("aa".repeat(32))
                .outputs(List.of(
                        TransactionOutput.builder().address(allowedAddr)
                                .amounts(List.of(lovelaceAmount(1000))).build(),
                        TransactionOutput.builder().address(blockedAddr)
                                .amounts(List.of(lovelaceAmount(2000))).build()
                ))
                .build();
        Block block = Block.builder()
                .era(Era.Babbage)
                .transactionBodies(List.of(tx))
                .invalidTransactions(Collections.emptyList())
                .build();

        publishBlock(10, 1, "bb".repeat(32), block);

        // Allowed address should have its UTXO
        var allowed = store.getUtxo(new Outpoint(tx.getTxHash(), 0));
        assertTrue(allowed.isPresent());
        assertEquals(new BigInteger("1000"), allowed.get().lovelace());

        // Blocked address should NOT have its UTXO
        var blocked = store.getUtxo(new Outpoint(tx.getTxHash(), 1));
        assertFalse(blocked.isPresent());
    }

    @Test
    void filterByPaymentCredential_matchingStored() {
        // Use a known payment credential hash
        String credHash = "ab".repeat(14); // 28 bytes hex

        var filter = new AddressUtxoFilter(Set.of(), Set.of(credHash));
        store.setFilterChain(new StorageFilterChain(List.of(filter)));

        // addr_test1... with matching cred won't match since we're using pseudo addresses
        // but we can test that non-matching addresses are filtered out
        String nonMatchAddr = "addr_test1vpxnomatch0000000000000000000000000000000000";
        TransactionBody tx = TransactionBody.builder()
                .txHash("cc".repeat(32))
                .outputs(List.of(
                        TransactionOutput.builder().address(nonMatchAddr)
                                .amounts(List.of(lovelaceAmount(500))).build()
                ))
                .build();
        Block block = Block.builder()
                .era(Era.Babbage)
                .transactionBodies(List.of(tx))
                .invalidTransactions(Collections.emptyList())
                .build();

        publishBlock(20, 2, "dd".repeat(32), block);

        // Non-matching address with non-matching cred should be filtered
        var result = store.getUtxo(new Outpoint(tx.getTxHash(), 0));
        assertFalse(result.isPresent());
    }

    @Test
    void noFilter_allUtxosStored() {
        // No filter chain set — all outputs should be stored
        String addr1 = "addr_test1vpxone000000000000000000000000000000000000000";
        String addr2 = "addr_test1vpxtwo000000000000000000000000000000000000000";

        TransactionBody tx = TransactionBody.builder()
                .txHash("ee".repeat(32))
                .outputs(List.of(
                        TransactionOutput.builder().address(addr1)
                                .amounts(List.of(lovelaceAmount(100))).build(),
                        TransactionOutput.builder().address(addr2)
                                .amounts(List.of(lovelaceAmount(200))).build()
                ))
                .build();
        Block block = Block.builder()
                .era(Era.Babbage)
                .transactionBodies(List.of(tx))
                .invalidTransactions(Collections.emptyList())
                .build();

        publishBlock(30, 3, "ff".repeat(32), block);

        assertTrue(store.getUtxo(new Outpoint(tx.getTxHash(), 0)).isPresent());
        assertTrue(store.getUtxo(new Outpoint(tx.getTxHash(), 1)).isPresent());
    }

    @Test
    void emptyFilterConfig_allUtxosStored() {
        // Empty address/cred sets → pass-through
        var filter = new AddressUtxoFilter(Set.of(), Set.of());
        store.setFilterChain(new StorageFilterChain(List.of(filter)));

        String addr = "addr_test1vpxempty00000000000000000000000000000000000000";
        TransactionBody tx = TransactionBody.builder()
                .txHash("11".repeat(32))
                .outputs(List.of(
                        TransactionOutput.builder().address(addr)
                                .amounts(List.of(lovelaceAmount(999))).build()
                ))
                .build();
        Block block = Block.builder()
                .era(Era.Babbage)
                .transactionBodies(List.of(tx))
                .invalidTransactions(Collections.emptyList())
                .build();

        publishBlock(40, 4, "22".repeat(32), block);

        assertTrue(store.getUtxo(new Outpoint(tx.getTxHash(), 0)).isPresent());
    }
}
