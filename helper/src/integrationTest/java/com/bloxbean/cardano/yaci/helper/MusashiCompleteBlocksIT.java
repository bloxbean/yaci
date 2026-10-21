package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.helper.model.Transaction;
import com.bloxbean.cardano.yaci.helper.model.leios.EndorserBlockUnavailableEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ADR 0013 against the live Musashi network: an unmodified listener receives certifying blocks complete.
 * Expected values are db-sync's (KleioScan explorer, 2026-10-20, Musashi rc-2026w42): #9100 has 1,035 transactions
 * (EB 8725…) with 5b8fb49e…7f4f at index 1034, #9101 has 1,035 (EB 7958…).
 * Run: YACI_MUSASHI_E2E=true ./gradlew :helper:integrationTest --tests '*MusashiCompleteBlocksIT*'
 */
@EnabledIfEnvironmentVariable(named = "YACI_MUSASHI_E2E", matches = "true")
class MusashiCompleteBlocksIT {
    private static final String HOST = "leios-node.play.dev.cardano.org";
    private static final int PORT = 3001;
    private static final long MAGIC = 164;
    private static final Point B9099 = new Point(185624, "c65c894d7a306d618ce8602653318f71a701ee583c4a7aa55b3a3e9fd72231d9");
    private static final Point B9100 = new Point(185680, "fa6d0b3e3ca432e3e7a7438fd29c59267936b3dd1f5c004ed7387973ae6d798f");
    private static final Point B9102 = new Point(185701, "42533ffea40d5021913b249578f9093923ea1b00bb851f2827550a0a14544bd4");

    @Test
    void rangeSyncDeliversCertifyingBlocksComplete() throws Exception {
        Map<Long, Delivered> blocks = fetch(B9099, B9102, 4);

        assertEquals(354, blocks.get(9099L).txCount);
        assertNull(blocks.get(9099L).ebHash);
        assertEquals(1035, blocks.get(9100L).txCount);
        assertTrue(blocks.get(9100L).ebHash.startsWith("8725f30a"));
        assertEquals(1035, blocks.get(9100L).ebTxCount);
        assertEquals("5b8fb49ef6a05fe169eef322af211465b5bf4cabac7148511accca21c3d87f4f", blocks.get(9100L).lastTxHash);
        assertEquals(1035, blocks.get(9101L).txCount);
        assertTrue(blocks.get(9101L).ebHash.startsWith("795814c5"));
        assertEquals(354, blocks.get(9102L).txCount);
    }

    @Test
    void rangeStartingAtACertifyingBlockLooksUpItsParent() throws Exception {
        //The first block certifies; its parent (#9099) was never seen, so Yaci finds it by hash
        Map<Long, Delivered> blocks = fetch(B9100, B9102, 3);

        assertEquals(1035, blocks.get(9100L).txCount);
        assertEquals(1035, blocks.get(9101L).txCount);
    }

    @Test
    void tipSyncDeliversCertifyingBlocksComplete() throws Exception {
        //Follow the live chain; every block that certifies an EB must arrive with its EB transactions
        Map<Long, Delivered> blocks = new ConcurrentHashMap<>();
        Map<Long, Boolean> certifying = new ConcurrentHashMap<>();
        BlockSync blockSync = new BlockSync(HOST, PORT, MAGIC, B9099);
        blockSync.startSyncFromTip(new BlockChainDataListener() {
            @Override
            public void onBlock(Era era, Block block, List<Transaction> transactions) {
                long number = block.getHeader().getHeaderBody().getBlockNumber();
                var ref = block.getLeiosEndorserBlock();
                blocks.put(number, new Delivered(transactions.size(), block.getTransactionBodies().size(),
                        ref != null ? ref.getEbHash() : null, ref != null ? ref.getTxCount() : 0, null));
                certifying.put(number, Boolean.TRUE.equals(block.getHeader().getHeaderBody().getLeiosCertified()));
            }

            @Override
            public void onEndorserBlockUnavailable(EndorserBlockUnavailableEvent event) {
                System.out.println("EB unavailable: " + event);
            }
        });
        try {
            TimeUnit.SECONDS.sleep(240);
        } finally {
            blockSync.stop();
        }

        long certified = certifying.values().stream().filter(c -> c).count();
        System.out.println("TIP blocks=" + blocks.size() + " certifying=" + certified + " " + new java.util.TreeMap<>(blocks));
        assertTrue(blocks.size() >= 3, "expected live blocks");
        //Certifying blocks only appear while the network is under load
        assumeTrue(certified >= 1, "no certifying block in 4 minutes");
        certifying.forEach((number, isCertifying) -> {
            Delivered d = blocks.get(number);
            assertEquals(d.txCount, d.bodyCount);
            if (isCertifying) {
                assertTrue(d.ebHash != null && d.ebTxCount > 0 && d.txCount >= d.ebTxCount,
                        "certifying block " + number + " must arrive complete: " + d);
            }
        });
    }

    private Map<Long, Delivered> fetch(Point from, Point to, int expectedBlocks) throws Exception {
        Map<Long, Delivered> blocks = new ConcurrentHashMap<>();
        CountDownLatch done = new CountDownLatch(1);
        BlockRangeSync rangeSync = new BlockRangeSync(HOST, PORT, MAGIC);
        rangeSync.start(new BlockChainDataListener() {
            @Override
            public void onBlock(Era era, Block block, List<Transaction> transactions) {
                var ref = block.getLeiosEndorserBlock();
                blocks.put(block.getHeader().getHeaderBody().getBlockNumber(), new Delivered(transactions.size(),
                        block.getTransactionBodies().size(), ref != null ? ref.getEbHash() : null,
                        ref != null ? ref.getTxCount() : 0,
                        transactions.isEmpty() ? null : transactions.get(transactions.size() - 1).getTxHash()));
            }

            @Override
            public void onEndorserBlockUnavailable(EndorserBlockUnavailableEvent event) {
                System.out.println("EB unavailable: " + event);
            }

            @Override
            public void batchDone() {
                done.countDown();
            }
        });
        try {
            rangeSync.fetch(from, to);
            assertTrue(done.await(5, TimeUnit.MINUTES), "range sync did not finish");
            assertEquals(expectedBlocks, blocks.size());
            blocks.values().forEach(d -> assertEquals(d.txCount, d.bodyCount, "Block lists and transaction list agree"));
            return blocks;
        } finally {
            rangeSync.stop();
        }
    }

    private record Delivered(int txCount, int bodyCount, String ebHash, int ebTxCount, String lastTxHash) {
    }
}
