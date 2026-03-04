package com.bloxbean.cardano.yaci.node.runtime.utxo;

import com.bloxbean.cardano.yaci.core.model.*;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.impl.SimpleEventBus;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import com.bloxbean.cardano.yaci.node.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yaci.node.runtime.chain.DirectRocksDBChainState;
import com.bloxbean.cardano.yaci.node.runtime.db.UtxoCfNames;
import com.bloxbean.cardano.yaci.node.runtime.events.BlockAppliedEvent;
import com.bloxbean.cardano.yaci.node.runtime.events.RollbackEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.math.BigInteger;
import java.nio.file.Files;
import java.util.*;

import static com.bloxbean.cardano.yaci.core.util.Constants.LOVELACE;
import static org.junit.jupiter.api.Assertions.*;

class ClassicUtxoStoreTest {
    private File tempDir;
    private DirectRocksDBChainState chain;
    private ClassicUtxoStore store;
    private EventBus bus;

    @BeforeEach
    void setup() throws Exception {
        tempDir = Files.createTempDirectory("yaci-utxo-test").toFile();
        chain = new DirectRocksDBChainState(tempDir.getAbsolutePath());
        bus = new SimpleEventBus();
        Logger log = LoggerFactory.getLogger(ClassicUtxoStoreTest.class);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("yaci.node.utxo.enabled", true);
        cfg.put("yaci.node.utxo.pruneDepth", 3);
        cfg.put("yaci.node.utxo.rollbackWindow", 4);
        cfg.put("yaci.node.utxo.pruneBatchSize", 100);
        store = new ClassicUtxoStore(chain, log, cfg);
        // register handler
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

    private void publishBlock(long slot, long blockNo, String hash, Block block) {
        bus.publish(new BlockAppliedEvent(Era.Babbage, slot, blockNo, hash, block),
                EventMetadata.builder().origin("test").slot(slot).blockNo(blockNo).blockHash(hash).build(),
                PublishOptions.builder().build());
    }

    private void publishRollback(long targetSlot) {
        bus.publish(new RollbackEvent(new Point(targetSlot, null), true),
                EventMetadata.builder().origin("test").slot(targetSlot).build(),
                PublishOptions.builder().build());
    }

    @Test
    void applyValidBlock_thenQueryByAddress_andByOutpoint() {
        String addr = "addr_test1vpxvalid0000000000000000000000000000000000000000"; // pseudo address
        TransactionOutput out0 = TransactionOutput.builder()
                .address(addr)
                .amounts(List.of(lovelaceAmount(1000)))
                .build();
        TransactionBody tx = TransactionBody.builder()
                .txHash("aa".repeat(32))
                .outputs(List.of(out0))
                .build();
        Block block = Block.builder()
                .era(Era.Babbage)
                .transactionBodies(List.of(tx))
                .invalidTransactions(Collections.emptyList())
                .build();

        publishBlock(10, 1, "bb".repeat(32), block);

        var list = store.getUtxosByAddress(addr, 1, 10);
        assertEquals(1, list.size());
        assertEquals(new BigInteger("1000"), list.get(0).lovelace());
        assertEquals(addr, list.get(0).address());

        var opt = store.getUtxo(new Outpoint(tx.getTxHash(), 0));
        assertTrue(opt.isPresent());
        assertEquals(new BigInteger("1000"), opt.get().lovelace());
    }

    @Test
    void applyInvalidBlock_usesCollateralAndReturn_only() {
        String addrA = "addr_test1vpxcollat000000000000000000000000000000000000";
        String addrRet = "addr_test1vpxreturn0000000000000000000000000000000000";

        TransactionOutput seedOut = TransactionOutput.builder()
                .address(addrA)
                .amounts(List.of(lovelaceAmount(2000)))
                .build();
        TransactionBody seedTx = TransactionBody.builder()
                .txHash("11".repeat(32))
                .outputs(List.of(seedOut))
                .build();
        Block seedBlock = Block.builder()
                .era(Era.Babbage)
                .transactionBodies(List.of(seedTx))
                .invalidTransactions(Collections.emptyList())
                .build();
        publishBlock(5, 1, "22".repeat(32), seedBlock);

        TransactionBody badTx = TransactionBody.builder()
                .txHash("aa".repeat(32))
                .collateralInputs(Set.of(TransactionInput.builder().transactionId(seedTx.getTxHash()).index(0).build()))
                .outputs(List.of(TransactionOutput.builder().address("ignored").amounts(List.of(lovelaceAmount(999))).build()))
                .collateralReturn(TransactionOutput.builder().address(addrRet).amounts(List.of(lovelaceAmount(1500))).build())
                .build();
        Block badBlock = Block.builder()
                .era(Era.Babbage)
                .transactionBodies(List.of(badTx))
                .invalidTransactions(List.of(0))
                .build();
        publishBlock(10, 2, "bb".repeat(32), badBlock);

        assertTrue(store.getUtxosByAddress(addrA, 1, 10).isEmpty());
        var utxosRet = store.getUtxosByAddress(addrRet, 1, 10);
        assertEquals(1, utxosRet.size());
        assertEquals(new BigInteger("1500"), utxosRet.get(0).lovelace());
        assertEquals(1, utxosRet.get(0).outpoint().index());
    }

    @Test
    void rollbackRevertsCreatedAndRestoresSpent() {
        String addr = "addr_test1vpxrollback00000000000000000000000000000000000";

        TransactionBody tx1 = TransactionBody.builder()
                .txHash("01".repeat(32))
                .outputs(List.of(TransactionOutput.builder().address(addr)
                        .amounts(List.of(lovelaceAmount(100))).build()))
                .build();
        Block b1 = Block.builder().era(Era.Babbage).transactionBodies(List.of(tx1)).invalidTransactions(Collections.emptyList()).build();
        publishBlock(100, 1, "a1".repeat(32), b1);

        TransactionBody tx2 = TransactionBody.builder()
                .txHash("02".repeat(32))
                .inputs(Set.of(TransactionInput.builder().transactionId(tx1.getTxHash()).index(0).build()))
                .outputs(List.of())
                .build();
        Block b2 = Block.builder().era(Era.Babbage).transactionBodies(List.of(tx2)).invalidTransactions(Collections.emptyList()).build();
        publishBlock(200, 2, "a2".repeat(32), b2);

        assertTrue(store.getUtxosByAddress(addr, 1, 10).isEmpty());
        publishRollback(150);

        var list = store.getUtxosByAddress(addr, 1, 10);
        assertEquals(1, list.size());
        assertEquals(new BigInteger("100"), list.get(0).lovelace());
    }

    @Test
    void pruneRespectsRollbackWindowForSpent() {
        String addr = "addr_test1vpxprune00000000000000000000000000000000000000";
        TransactionBody tx1 = TransactionBody.builder()
                .txHash("f1".repeat(32))
                .outputs(List.of(TransactionOutput.builder().address(addr)
                        .amounts(List.of(lovelaceAmount(50))).build()))
                .build();
        Block b1 = Block.builder().era(Era.Babbage).transactionBodies(List.of(tx1)).invalidTransactions(Collections.emptyList()).build();
        publishBlock(10, 1, "c1".repeat(32), b1);

        TransactionBody tx2 = TransactionBody.builder()
                .txHash("f2".repeat(32))
                .inputs(Set.of(TransactionInput.builder().transactionId(tx1.getTxHash()).index(0).build()))
                .outputs(List.of())
                .build();
        Block b2 = Block.builder().era(Era.Babbage).transactionBodies(List.of(tx2)).invalidTransactions(Collections.emptyList()).build();
        publishBlock(11, 2, "c2".repeat(32), b2);

        for (int i = 0; i < 3; i++) {
            Block empty = Block.builder().era(Era.Babbage).transactionBodies(Collections.emptyList()).invalidTransactions(Collections.emptyList()).build();
            publishBlock(12 + i, 3 + i, "dd".repeat(32), empty);
        }

        publishRollback(11);
        var list = store.getUtxosByAddress(addr, 1, 10);
        assertFalse(list.isEmpty());
    }

    @Test
    void indexBothAddressHashAndPaymentCredential() throws Exception {
        byte[] cred = new byte[28];
        for (int i = 0; i < 28; i++) cred[i] = (byte)(i + 1);
        byte[] raw = new byte[1 + 28];
        raw[0] = 0x60; // type=6, net=0
        System.arraycopy(cred, 0, raw, 1, 28);
        String addrHex = com.bloxbean.cardano.yaci.core.util.HexUtil.encodeHexString(raw);

        TransactionBody tx = TransactionBody.builder()
                .txHash("de".repeat(32))
                .outputs(List.of(TransactionOutput.builder().address(addrHex)
                        .amounts(List.of(lovelaceAmount(42))).build()))
                .build();
        Block b = Block.builder().era(Era.Babbage).transactionBodies(List.of(tx)).invalidTransactions(Collections.emptyList()).build();
        long slot = 1000;
        long blockNo = 10;
        publishBlock(slot, blockNo, "ab".repeat(32), b);

        var cfAddr = chain.rocks().handle(UtxoCfNames.UTXO_ADDR);
        byte[] addrHash28 = UtxoKeyUtil.addrHash28(addrHex);
        byte[] k1 = UtxoKeyUtil.addressIndexKey(addrHash28, slot, tx.getTxHash(), 0);
        byte[] payCred28 = UtxoKeyUtil.paymentCred28(addrHex);
        assertNotNull(payCred28);
        byte[] k2 = UtxoKeyUtil.addressIndexKey(payCred28, slot, tx.getTxHash(), 0);
        assertNotNull(chain.rocks().db().get(cfAddr, k1));
        assertNotNull(chain.rocks().db().get(cfAddr, k2));

        TransactionBody spend = TransactionBody.builder()
                .txHash("ef".repeat(32))
                .inputs(Set.of(TransactionInput.builder().transactionId(tx.getTxHash()).index(0).build()))
                .outputs(List.of())
                .build();
        Block b2 = Block.builder().era(Era.Babbage).transactionBodies(List.of(spend)).invalidTransactions(Collections.emptyList()).build();
        publishBlock(slot + 1, blockNo + 1, "ac".repeat(32), b2);

        assertNull(chain.rocks().db().get(cfAddr, k1));
        assertNull(chain.rocks().db().get(cfAddr, k2));
    }


    Amount lovelaceAmount(long lovelace) {
        return Amount.builder()
                .unit(LOVELACE)
                .quantity(BigInteger.valueOf(lovelace))
                .build();
    }
}
