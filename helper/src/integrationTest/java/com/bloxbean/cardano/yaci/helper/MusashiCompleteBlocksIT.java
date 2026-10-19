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

/**
 * ADR 0013 against the live Musashi network: an unmodified listener receives certifying blocks complete.
 * Expected values are db-sync's (KleioScan explorer, 2026-10-17): #156613 has 345 transactions (EB 3bca…),
 * #156614 has 5,255 (EB 7771…) with c82dae27…2327 at index 5254.
 * Run: YACI_MUSASHI_E2E=true ./gradlew :helper:integrationTest --tests '*MusashiCompleteBlocksIT*'
 */
@EnabledIfEnvironmentVariable(named = "YACI_MUSASHI_E2E", matches = "true")
class MusashiCompleteBlocksIT {
    private static final String HOST = "leios-node.play.dev.cardano.org";
    private static final int PORT = 3001;
    private static final long MAGIC = 164;
    private static final Point B156612 = new Point(3495634, "e959076b6010c7fb81f5505b9737f0dcf63c5ccced84e758cbc7d1103da14b65");
    private static final Point B156613 = new Point(3495655, "f9931423392728009f6c48580552481c55fb94d873198301d583f2674aa6ce2c");
    private static final Point B156615 = new Point(3495679, "4f6517fade24de13a7d4dca9992c8332aa0c1edabf4ace967fe90fafff348d98");

    @Test
    void rangeSyncDeliversCertifyingBlocksComplete() throws Exception {
        Map<Long, Delivered> blocks = fetch(B156612, B156615, 4);

        assertEquals(354, blocks.get(156612L).txCount);
        assertNull(blocks.get(156612L).ebHash);
        assertEquals(345, blocks.get(156613L).txCount);
        assertTrue(blocks.get(156613L).ebHash.startsWith("3bca1a2b"));
        assertEquals(345, blocks.get(156613L).ebTxCount);
        assertEquals(5255, blocks.get(156614L).txCount);
        assertTrue(blocks.get(156614L).ebHash.startsWith("77714b7b"));
        assertEquals("c82dae27a8c6271ac48290900eaa5b3cf72e8e08fd4138dba15f13ed71f32327", blocks.get(156614L).lastTxHash);
        assertEquals(356, blocks.get(156615L).txCount);
    }

    @Test
    void rangeStartingAtACertifyingBlockLooksUpItsParent() throws Exception {
        //The first block certifies; its parent (#156612) was never seen, so Yaci finds it by hash
        Map<Long, Delivered> blocks = fetch(B156613, B156615, 3);

        assertEquals(345, blocks.get(156613L).txCount);
        assertEquals(5255, blocks.get(156614L).txCount);
    }

    @Test
    void tipSyncDeliversCertifyingBlocksComplete() throws Exception {
        //Follow the live chain; every block that certifies an EB must arrive with its EB transactions
        Map<Long, Delivered> blocks = new ConcurrentHashMap<>();
        Map<Long, Boolean> certifying = new ConcurrentHashMap<>();
        BlockSync blockSync = new BlockSync(HOST, PORT, MAGIC, B156612);
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
        assertTrue(certified >= 1, "expected at least one certifying block in 4 minutes");
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
