package com.bloxbean.cardano.yaci.helper;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.model.leios.LeiosAnnouncement;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosPoint;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.helper.model.Transaction;
import com.bloxbean.cardano.yaci.helper.model.leios.EndorserBlockUnavailableEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompleteBlockAssemblerTest {
    private static final String EB_HASH = "ee".repeat(32);

    private final List<String> delivered = new CopyOnWriteArrayList<>();
    private final List<Block> blocks = new CopyOnWriteArrayList<>();
    private final List<EndorserBlockUnavailableEvent> unavailable = new CopyOnWriteArrayList<>();
    private final List<String> threads = new CopyOnWriteArrayList<>();
    private CompleteBlockAssembler assembler;

    private final BlockChainDataListener app = new BlockChainDataListener() {
        @Override
        public void onBlock(Era era, Block block, List<Transaction> transactions) {
            threads.add(Thread.currentThread().getName());
            blocks.add(block);
            delivered.add("block " + block.getHeader().getHeaderBody().getBlockNumber() + " txs=" + transactions.size());
        }

        @Override
        public void onRollback(Point point) {
            threads.add(Thread.currentThread().getName());
            delivered.add("rollback " + point.getSlot());
        }

        @Override
        public void batchDone() {
            threads.add(Thread.currentThread().getName());
            delivered.add("batchDone");
        }

        @Override
        public void onEndorserBlockUnavailable(EndorserBlockUnavailableEvent event) {
            unavailable.add(event);
        }
    };

    @AfterEach
    void close() {
        if (assembler != null) assembler.close();
    }

    @Test
    void certifyingBlockArrivesCompleteAndInOrder() throws Exception {
        CompletableFuture<List<byte[]>> eb = new CompletableFuture<>();
        AtomicReference<LeiosPoint> requested = new AtomicReference<>();
        assembler = assembler(point -> {
            requested.set(point);
            return eb;
        }, null, false);

        assembler.onBlock(Era.Dijkstra, block(1, 100, "h1", "h0", true, false), List.of());
        assembler.onBlock(Era.Dijkstra, block(2, 120, "h2", "h1", false, true), List.of());
        assembler.onBlock(Era.Dijkstra, block(3, 130, "h3", "h2", false, false), List.of());
        assembler.batchDone();

        waitUntil(() -> delivered.size() == 1);
        Thread.sleep(200);
        assertEquals(List.of("block 1 txs=0"), delivered); //block 2 waits for its EB, block 3 and batchDone behind it
        assertEquals(100, requested.get().getSlot());      //EB point = announcing (parent) block's slot and EB hash
        assertEquals(EB_HASH, HexUtil.encodeHexString(requested.get().getEbHash()));

        eb.complete(List.of(ebTx(1), ebTx(2)));

        waitUntil(() -> delivered.size() == 4);
        assertEquals(List.of("block 1 txs=0", "block 2 txs=2", "block 3 txs=0", "batchDone"), delivered);
        Block complete = blocks.get(1);
        assertEquals(2, complete.getLeiosEndorserBlock().getTxCount());
        assertEquals(EB_HASH, complete.getLeiosEndorserBlock().getEbHash());
        assertEquals(1, threads.stream().distinct().count());
        assertEquals("yaci-leios-complete-blocks", threads.get(0));
    }

    @Test
    void blocksWithoutCertificatePassThroughUnchanged() throws Exception {
        assembler = assembler(point -> {
            throw new AssertionError("no EB should be fetched");
        }, null, false);
        Block plain = block(1, 100, "h1", "h0", false, false);
        List<Transaction> txs = List.of(Transaction.builder().txHash("t").build());
        List<List<Transaction>> received = new ArrayList<>();
        assembler.close();
        assembler = new CompleteBlockAssembler(new BlockChainDataListener() {
            @Override
            public void onBlock(Era era, Block block, List<Transaction> transactions) {
                blocks.add(block);
                received.add(transactions);
            }
        }, failingSource(), (h, s, p) -> null, false, 1);

        assembler.onBlock(Era.Dijkstra, plain, txs);

        waitUntil(() -> blocks.size() == 1);
        assertTrue(plain == blocks.get(0));
        assertTrue(txs == received.get(0));
    }

    @Test
    void unknownParentIsLookedUpFromTheStartPoint() throws Exception {
        AtomicReference<Object[]> lookup = new AtomicReference<>();
        Point start = new Point(100, "h1");
        assembler = assembler(point -> CompletableFuture.completedFuture(List.of(ebTx(5))), (hash, childSlot, known) -> {
            lookup.set(new Object[]{hash, childSlot, known});
            return block(1, 100, "h1", "h0", true, false);
        }, false);
        assembler.setStartPoint(start);

        assembler.onBlock(Era.Dijkstra, block(2, 120, "h2", "h1", false, true), List.of());

        waitUntil(() -> delivered.size() == 1);
        assertEquals("block 2 txs=1", delivered.get(0));
        assertEquals("h1", lookup.get()[0]);
        assertEquals(120L, lookup.get()[1]);
        assertEquals(start, lookup.get()[2]);
    }

    @Test
    void unavailableEndorserBlockIsReportedAndRetried() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        //call 1 is the early fetch when the block arrives; a failed early fetch is retried once before reporting
        assembler = assembler(point -> calls.incrementAndGet() <= 2
                ? CompletableFuture.failedFuture(new IllegalStateException("not found"))
                : CompletableFuture.completedFuture(List.of(ebTx(1))), null, false);

        assembler.onBlock(Era.Dijkstra, block(1, 100, "h1", "h0", true, false), List.of());
        assembler.onBlock(Era.Dijkstra, block(2, 120, "h2", "h1", false, true), List.of());

        waitUntil(() -> delivered.size() == 2);
        assertEquals("block 2 txs=1", delivered.get(1));
        assertEquals(1, unavailable.size());
        assertTrue(unavailable.get(0).isWillRetry());
        assertEquals(2, unavailable.get(0).getCertifyingBlockNumber());
    }

    @Test
    void skipModeDeliversWithoutEndorserBlockAfterThreeAttempts() throws Exception {
        assembler = assembler(point -> CompletableFuture.failedFuture(new IllegalStateException("gone")), null, true);

        assembler.onBlock(Era.Dijkstra, block(1, 100, "h1", "h0", true, false), List.of());
        assembler.onBlock(Era.Dijkstra, block(2, 120, "h2", "h1", false, true), List.of());

        waitUntil(() -> delivered.size() == 2);
        assertEquals("block 2 txs=0", delivered.get(1));
        assertNull(blocks.get(1).getLeiosEndorserBlock());
        assertEquals(3, unavailable.size());
        assertTrue(unavailable.get(1).isWillRetry());
        assertFalse(unavailable.get(2).isWillRetry());
    }

    @Test
    void rollbackReleasesABlockWaitingForItsEndorserBlock() throws Exception {
        assembler = assembler(point -> new CompletableFuture<>(), null, false); //never resolves

        assembler.onBlock(Era.Dijkstra, block(1, 100, "h1", "h0", true, false), List.of());
        assembler.onBlock(Era.Dijkstra, block(2, 120, "h2", "h1", false, true), List.of());
        waitUntil(() -> delivered.size() == 1);
        assembler.onRollback(new Point(100, "h1"));

        waitUntil(() -> delivered.size() == 3);
        assertEquals(List.of("block 1 txs=0", "block 2 txs=0", "rollback 100"), delivered);
    }

    @Test
    void stopWhileQueueIsFullDoesNotDeadlock() throws Exception {
        //Yaci Store stops the sync from inside onBlock (stopSyncOnError) while the network thread waits for queue
        //space. stop() closes the assembler first (releasing that thread), then shuts the connection down, which
        //needs the network thread
        java.util.concurrent.ExecutorService networkThread = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.CountDownLatch stopReturned = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean(true);
        AtomicReference<CompleteBlockAssembler> self = new AtomicReference<>();
        assembler = new CompleteBlockAssembler(new BlockChainDataListener() {
            @Override
            public void onBlock(Era era, Block block, List<Transaction> transactions) {
                if (!first.compareAndSet(true, false)) return;
                try {
                    Thread.sleep(300);                           //busy: the network fills the queue meanwhile
                    self.get().close();                          //BlockSync/BlockRangeSync.stop(): assembler first
                    networkThread.submit(() -> { }).get();       //then the connection shutdown (needs the thread)
                    stopReturned.countDown();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }, source(point -> new CompletableFuture<>()), (h, s, p) -> null, false, 1);
        self.set(assembler);
        networkThread.submit(() -> {
            for (int i = 1; i <= CompleteBlockAssembler.QUEUE_CAPACITY + 100; i++) {
                assembler.onBlock(Era.Dijkstra, block(i, i * 20L, "h" + i, "h" + (i - 1), false, false), List.of());
            }
        });

        assertTrue(stopReturned.await(10, java.util.concurrent.TimeUnit.SECONDS), "stop() must not deadlock");
        networkThread.shutdownNow();
    }

    @Test
    void closeNeverInterruptsApplicationCode() throws Exception {
        java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1);
        AtomicReference<Boolean> interrupted = new AtomicReference<>();
        assembler = new CompleteBlockAssembler(new BlockChainDataListener() {
            @Override
            public void onBlock(Era era, Block block, List<Transaction> transactions) {
                inside.countDown();
                long until = System.currentTimeMillis() + 300;
                while (System.currentTimeMillis() < until) { /* e.g. a database transaction */ }
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        }, source(point -> new CompletableFuture<>()), (h, s, p) -> null, false, 1);
        assembler.onBlock(Era.Dijkstra, block(1, 100, "h1", "h0", false, false), List.of());
        inside.await();

        assembler.close();

        waitUntil(() -> interrupted.get() != null);
        assertFalse(interrupted.get());
    }

    @Test
    void exceptionFromOnBlockIsReportedAsParsingErrorAsBefore() throws Exception {
        List<com.bloxbean.cardano.yaci.core.exception.BlockParseRuntimeException> errors = new CopyOnWriteArrayList<>();
        assembler = new CompleteBlockAssembler(new BlockChainDataListener() {
            @Override
            public void onBlock(Era era, Block block, List<Transaction> transactions) {
                throw new IllegalStateException("database down");
            }

            @Override
            public void onParsingError(com.bloxbean.cardano.yaci.core.exception.BlockParseRuntimeException e) {
                errors.add(e);
            }
        }, source(point -> new CompletableFuture<>()), (h, s, p) -> null, false, 1);

        assembler.onBlock(Era.Dijkstra, block(7, 100, "h7", "h6", false, false), List.of());

        waitUntil(() -> errors.size() == 1);
        assertEquals(7L, errors.get(0).getBlockNumber());
    }

    @Test
    void timedOutEndorserBlockIsRequestedAgain() throws Exception {
        //A request that never answers: on timeout the pending request is cancelled so the retry sends a new one
        List<CompletableFuture<List<byte[]>>> requests = new CopyOnWriteArrayList<>();
        assembler = assembler(point -> {
            CompletableFuture<List<byte[]>> f = new CompletableFuture<>();
            requests.add(f);
            return f;
        }, null, false);
        assembler.onBlock(Era.Dijkstra, block(1, 100, "h1", "h0", true, false), List.of());
        assembler.onBlock(Era.Dijkstra, block(2, 120, "h2", "h1", false, true), List.of());
        waitUntil(() -> requests.size() >= 1);

        requests.get(0).cancel(false); //what the 120 s timeout does to the pending request
        waitUntil(() -> requests.size() >= 2);
        requests.get(requests.size() - 1).complete(List.of(ebTx(1)));

        waitUntil(() -> delivered.size() == 2);
        assertEquals("block 2 txs=1", delivered.get(1));
    }

    private static CompleteBlockAssembler.EndorserBlockSource source(
            java.util.function.Function<LeiosPoint, CompletableFuture<List<byte[]>>> source) {
        return new CompleteBlockAssembler.EndorserBlockSource() {
            @Override
            public CompletableFuture<List<byte[]>> resolve(LeiosPoint point) {
                return source.apply(point);
            }

            @Override
            public void close() {
            }
        };
    }

    private CompleteBlockAssembler assembler(java.util.function.Function<LeiosPoint, CompletableFuture<List<byte[]>>> source,
                                             CompleteBlockAssembler.ParentBlockSource parents, boolean skip) {
        return new CompleteBlockAssembler(app, new CompleteBlockAssembler.EndorserBlockSource() {
            @Override
            public CompletableFuture<List<byte[]>> resolve(LeiosPoint point) {
                return source.apply(point);
            }

            @Override
            public void close() {
            }
        }, parents != null ? parents : (h, s, p) -> {
            throw new AssertionError("parent lookup not expected");
        }, skip, 1);
    }

    private static CompleteBlockAssembler.EndorserBlockSource failingSource() {
        return new CompleteBlockAssembler.EndorserBlockSource() {
            @Override
            public CompletableFuture<List<byte[]>> resolve(LeiosPoint point) {
                throw new AssertionError("no EB should be fetched");
            }

            @Override
            public void close() {
            }
        };
    }

    private static Block block(long number, long slot, String hash, String prevHash, boolean announces, boolean certifies) {
        HeaderBody headerBody = HeaderBody.builder()
                .blockNumber(number)
                .slot(slot)
                .blockHash(hash)
                .prevHash(prevHash)
                .leiosAnnouncement(announces ? new LeiosAnnouncement(EB_HASH, 1000) : null)
                .leiosCertified(certifies ? Boolean.TRUE : Boolean.FALSE)
                .build();
        return Block.builder()
                .era(Era.Dijkstra)
                .header(BlockHeader.builder().headerBody(headerBody).build())
                .transactionBodies(new ArrayList<>())
                .transactionWitness(new ArrayList<>())
                .auxiliaryDataMap(new java.util.LinkedHashMap<>())
                .invalidTransactions(Collections.emptyList())
                .build();
    }

    //w40a mempool encoding [body, witness_set, nil]
    private static byte[] ebTx(long fee) {
        Map body = new Map();
        body.put(new UnsignedInteger(0), new Array());
        body.put(new UnsignedInteger(1), new Array());
        body.put(new UnsignedInteger(2), new UnsignedInteger(fee));
        Array tx = new Array();
        tx.add(body);
        tx.add(new Map());
        tx.add(SimpleValue.NULL);
        return CborSerializationUtil.serialize(tx, false);
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("timed out");
            Thread.sleep(10);
        }
    }
}
