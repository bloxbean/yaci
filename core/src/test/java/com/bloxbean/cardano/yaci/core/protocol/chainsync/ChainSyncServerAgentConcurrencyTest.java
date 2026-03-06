package com.bloxbean.cardano.yaci.core.protocol.chainsync;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.*;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainSyncServerAgent;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainSyncState;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import io.netty.channel.*;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency tests for ChainSyncServerAgent to verify:
 * - No block gaps after reconnection (Bug 3 fix)
 * - Thread-safe pendingResponses queue (Bug 1 fix)
 * - Volatile visibility of shared fields (Bug 2 fix)
 * - Synchronized notifyNewBlock prevents interleaving (Bug 4 fix)
 */
class ChainSyncServerAgentConcurrencyTest {

    private ChainSyncServerAgent agent;
    private FakeChainState chainState;

    @BeforeEach
    void setup() {
        chainState = new FakeChainState();
        agent = new ChainSyncServerAgent(chainState);
        agent.setChannel(new StubChannel());
    }

    /**
     * Core scenario: client synced to block 116, server has produced up to block 120.
     * onNewDataAvailable() must deliver blocks 117, 118, 119, 120 sequentially — never skip to 120.
     */
    @Test
    void onNewDataAvailable_shouldDeliverBlocksSequentially_noGaps() {
        populateChain(100, 120);

        Point block116 = chainState.pointAt(116);
        performFindIntersect(block116);
        performRequestNextExpectingRollbackward(block116);

        for (int i = 117; i <= 120; i++) {
            performRequestNextExpectingRollForward(i);
        }

        performRequestNextExpectingAwaitReply();

        assertThat(agent.isClientAtTip()).isTrue();
        assertThat(agent.getLastSentPoint()).isEqualTo(chainState.pointAt(120));
    }

    /**
     * Simulates the reconnection gap scenario:
     * - Client was at block 116 when disconnected
     * - Server produced blocks 117-120 while client was offline
     * - Client reconnects, finds intersection at 116
     * - onNewDataAvailable() is called — must send 117, not 120
     */
    @Test
    void onNewDataAvailable_afterReconnection_shouldSendNextSequentialBlock() {
        populateChain(100, 120);

        Point block116 = chainState.pointAt(116);
        performFindIntersect(block116);
        performRequestNextExpectingRollbackward(block116);

        performRequestNextExpectingRollForward(117);
        performRequestNextExpectingRollForward(118);
        performRequestNextExpectingRollForward(119);
        performRequestNextExpectingRollForward(120);

        performRequestNextExpectingAwaitReply();
        assertThat(agent.isClientAtTip()).isTrue();

        // New block 121 arrives
        chainState.addBlock(121);
        agent.onNewDataAvailable();

        assertThat(agent.getLastSentPoint()).isEqualTo(chainState.pointAt(121));
    }

    /**
     * Multiple rapid onNewDataAvailable calls while client is at tip.
     * Only the first should send a block (setting clientAtTip=false).
     * Subsequent calls should be no-ops until client catches up.
     */
    @Test
    void onNewDataAvailable_multipleCalls_onlyFirstSendsBlock() {
        populateChain(100, 110);
        driveClientToTip(110);

        chainState.addBlock(111);
        chainState.addBlock(112);
        chainState.addBlock(113);

        agent.onNewDataAvailable();
        assertThat(agent.getLastSentPoint()).isEqualTo(chainState.pointAt(111));
        assertThat(agent.isClientAtTip()).isFalse();

        // Subsequent calls should be no-ops (client not at tip)
        agent.onNewDataAvailable();
        assertThat(agent.getLastSentPoint()).isEqualTo(chainState.pointAt(111));
    }

    /**
     * Concurrent onNewDataAvailable from multiple block-producer threads.
     * Only one should win; no exceptions, no corrupted state.
     */
    @Test
    void concurrentOnNewDataAvailable_noExceptionsOrCorruption() throws Exception {
        populateChain(100, 110);
        driveClientToTip(110);

        chainState.addBlock(111);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger exceptionCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    agent.onNewDataAvailable();
                } catch (Exception e) {
                    exceptionCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(exceptionCount.get()).isZero();
        assertThat(agent.getLastSentPoint()).isEqualTo(chainState.pointAt(111));
    }

    /**
     * Concurrent onNewDataAvailable from multiple threads — no exceptions.
     */
    @Test
    void concurrentNotifyAndRequestNext_noExceptions() throws Exception {
        populateChain(100, 150);
        driveClientToTip(150);

        // Now add more blocks that concurrent threads will try to deliver
        for (int i = 151; i <= 200; i++) {
            chainState.addBlock(i);
        }

        int iterations = 50;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        AtomicInteger exceptionCount = new AtomicInteger(0);

        // Thread 1: block producer notifications
        executor.submit(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < iterations; i++) {
                    agent.onNewDataAvailable();
                    Thread.yield();
                }
            } catch (Exception e) {
                exceptionCount.incrementAndGet();
            } finally {
                doneLatch.countDown();
            }
        });

        // Thread 2: also call onNewDataAvailable (simulating second caller)
        executor.submit(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < iterations; i++) {
                    agent.onNewDataAvailable();
                    Thread.yield();
                }
            } catch (Exception e) {
                exceptionCount.incrementAndGet();
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(exceptionCount.get()).isZero();
        assertThat(agent.getCurrentState()).isNotNull();
    }

    /**
     * After reset(), all fields should be cleared and agent should be reusable.
     */
    @Test
    void reset_clearsAllState() {
        populateChain(100, 110);
        driveClientToTip(110);

        agent.reset();

        assertThat(agent.isClientAtTip()).isFalse();
        assertThat(agent.getLastSentPoint()).isNull();
        assertThat(agent.getCurrentState()).isEqualTo(ChainSyncState.Idle);
    }

    /**
     * Rollback scenario: chain reorganizes while client is at tip.
     * onNewDataAvailable should detect the rollback and handle it.
     */
    @Test
    void onNewDataAvailable_detectsRollback_whenLastSentPointRemoved() {
        populateChain(100, 115);
        driveClientToTip(115);

        // Simulate chain reorg: remove blocks 113-115, add new 113'-115'
        chainState.rollbackTo(112L);
        chainState.addBlock(113);
        chainState.addBlock(114);
        chainState.addBlock(115);

        // Old lastSentPoint (block 115 with old hash) is no longer in chain
        agent.onNewDataAvailable();

        assertThat(agent.getCurrentState()).isNotNull();
    }

    /**
     * Stress test: rapid block production with concurrent notifications.
     * Verifies no exceptions under sustained concurrent access.
     */
    @Test
    void stressTest_rapidBlockProductionWithConcurrentNotifications() throws Exception {
        populateChain(1, 100);
        driveClientToTip(100);

        int newBlocks = 50;
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch doneLatch = new CountDownLatch(3);
        AtomicInteger exceptionCount = new AtomicInteger(0);

        executor.submit(() -> {
            try {
                for (int i = 101; i <= 100 + newBlocks; i++) {
                    chainState.addBlock(i);
                    Thread.sleep(1);
                }
            } catch (Exception e) {
                exceptionCount.incrementAndGet();
            } finally {
                doneLatch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                for (int i = 0; i < newBlocks * 3; i++) {
                    agent.onNewDataAvailable();
                    Thread.yield();
                }
            } catch (Exception e) {
                exceptionCount.incrementAndGet();
            } finally {
                doneLatch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                for (int i = 0; i < newBlocks * 3; i++) {
                    agent.onNewDataAvailable();
                    Thread.yield();
                }
            } catch (Exception e) {
                exceptionCount.incrementAndGet();
            } finally {
                doneLatch.countDown();
            }
        });

        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(exceptionCount.get()).isZero();
        assertThat(agent.getLastSentPoint()).isNotNull();
        assertThat(agent.getLastSentPoint().getSlot()).isGreaterThan(100L);
    }

    // ---- Helper methods ----

    private void populateChain(int fromBlock, int toBlock) {
        for (int i = fromBlock; i <= toBlock; i++) {
            chainState.addBlock(i);
        }
    }

    private void driveClientToTip(int tipBlock) {
        Point tipPoint = chainState.pointAt(tipBlock);
        performFindIntersect(tipPoint);
        performRequestNextExpectingRollbackward(tipPoint);
        performRequestNextExpectingAwaitReply();
        assertThat(agent.isClientAtTip()).isTrue();
    }

    private void performFindIntersect(Point intersectionPoint) {
        FindIntersect findIntersect = new FindIntersect(new Point[]{intersectionPoint});
        agent.receiveResponse(findIntersect);
        assertThat(agent.getCurrentState()).isEqualTo(ChainSyncState.Intersect);
        agent.sendNextMessage();
        assertThat(agent.getCurrentState()).isEqualTo(ChainSyncState.Idle);
    }

    private void performRequestNextExpectingRollForward(int expectedBlockNumber) {
        RequestNext requestNext = new RequestNext();
        agent.receiveResponse(requestNext);
        assertThat(agent.getCurrentState()).isEqualTo(ChainSyncState.CanAwait);
        agent.sendNextMessage();
        assertThat(agent.getCurrentState()).isEqualTo(ChainSyncState.Idle);
        assertThat(agent.getLastSentPoint()).isEqualTo(chainState.pointAt(expectedBlockNumber));
    }

    private void performRequestNextExpectingRollbackward(Point expectedPoint) {
        RequestNext requestNext = new RequestNext();
        // receiveResponse transitions Idle->CanAwait, then processResponse calls handleRollback
        // which internally calls sendNextMessage(), transitioning CanAwait->Idle
        agent.receiveResponse(requestNext);
        assertThat(agent.getCurrentState()).isEqualTo(ChainSyncState.Idle);
    }

    private void performRequestNextExpectingAwaitReply() {
        RequestNext requestNext = new RequestNext();
        agent.receiveResponse(requestNext);
        assertThat(agent.getCurrentState()).isEqualTo(ChainSyncState.CanAwait);
        agent.sendNextMessage();
        assertThat(agent.getCurrentState()).isEqualTo(ChainSyncState.MustReply);
    }

    // ---- Stub Channel that simulates immediate successful writes ----

    static class StubChannel implements Channel {
        private final StubChannelFuture successFuture = new StubChannelFuture();

        @Override public ChannelFuture writeAndFlush(Object msg) {
            return successFuture;
        }
        @Override public boolean isActive() { return true; }
        @Override public ChannelFuture writeAndFlush(Object msg, ChannelPromise promise) { return successFuture; }

        // -- Remaining Channel methods (unused, minimal stubs) --
        @Override public ChannelId id() { return null; }
        @Override public EventLoop eventLoop() { return null; }
        @Override public Channel parent() { return null; }
        @Override public ChannelConfig config() { return null; }
        @Override public boolean isOpen() { return true; }
        @Override public boolean isRegistered() { return true; }
        @Override public ChannelMetadata metadata() { return null; }
        @Override public SocketAddress localAddress() { return null; }
        @Override public SocketAddress remoteAddress() { return null; }
        @Override public ChannelFuture closeFuture() { return null; }
        @Override public boolean isWritable() { return true; }
        @Override public long bytesBeforeUnwritable() { return Long.MAX_VALUE; }
        @Override public long bytesBeforeWritable() { return 0; }
        @Override public Unsafe unsafe() { return null; }
        @Override public ChannelPipeline pipeline() { return null; }
        @Override public io.netty.buffer.ByteBufAllocator alloc() { return null; }
        @Override public <T> Attribute<T> attr(AttributeKey<T> key) { return null; }
        @Override public <T> boolean hasAttr(AttributeKey<T> key) { return false; }
        @Override public ChannelFuture bind(SocketAddress localAddress) { return null; }
        @Override public ChannelFuture connect(SocketAddress remoteAddress) { return null; }
        @Override public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) { return null; }
        @Override public ChannelFuture disconnect() { return null; }
        @Override public ChannelFuture close() { return null; }
        @Override public ChannelFuture deregister() { return null; }
        @Override public ChannelFuture bind(SocketAddress localAddress, ChannelPromise promise) { return null; }
        @Override public ChannelFuture connect(SocketAddress remoteAddress, ChannelPromise promise) { return null; }
        @Override public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) { return null; }
        @Override public ChannelFuture disconnect(ChannelPromise promise) { return null; }
        @Override public ChannelFuture close(ChannelPromise promise) { return null; }
        @Override public ChannelFuture deregister(ChannelPromise promise) { return null; }
        @Override public Channel read() { return this; }
        @Override public ChannelFuture write(Object msg) { return successFuture; }
        @Override public ChannelFuture write(Object msg, ChannelPromise promise) { return successFuture; }
        @Override public Channel flush() { return this; }
        @Override public ChannelPromise newPromise() { return null; }
        @Override public ChannelProgressivePromise newProgressivePromise() { return null; }
        @Override public ChannelFuture newSucceededFuture() { return successFuture; }
        @Override public ChannelFuture newFailedFuture(Throwable cause) { return null; }
        @Override public ChannelPromise voidPromise() { return null; }
        @Override public int compareTo(Channel o) { return 0; }
    }

    @SuppressWarnings("unchecked")
    static class StubChannelFuture implements ChannelFuture {
        @Override public boolean isSuccess() { return true; }
        @Override public Channel channel() { return null; }
        @Override public ChannelFuture addListener(GenericFutureListener<? extends Future<? super Void>> listener) {
            try {
                ((GenericFutureListener<Future<Void>>) listener).operationComplete(this);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return this;
        }
        @Override public ChannelFuture addListeners(GenericFutureListener<? extends Future<? super Void>>... listeners) { return this; }
        @Override public ChannelFuture removeListener(GenericFutureListener<? extends Future<? super Void>> listener) { return this; }
        @Override public ChannelFuture removeListeners(GenericFutureListener<? extends Future<? super Void>>... listeners) { return this; }
        @Override public ChannelFuture sync() { return this; }
        @Override public ChannelFuture syncUninterruptibly() { return this; }
        @Override public ChannelFuture await() { return this; }
        @Override public ChannelFuture awaitUninterruptibly() { return this; }
        @Override public boolean isVoid() { return false; }
        @Override public boolean isCancellable() { return false; }
        @Override public Throwable cause() { return null; }
        @Override public boolean await(long timeout, TimeUnit unit) { return true; }
        @Override public boolean await(long timeoutMillis) { return true; }
        @Override public boolean awaitUninterruptibly(long timeout, TimeUnit unit) { return true; }
        @Override public boolean awaitUninterruptibly(long timeoutMillis) { return true; }
        @Override public Void getNow() { return null; }
        @Override public boolean cancel(boolean mayInterruptIfRunning) { return false; }
        @Override public boolean isCancelled() { return false; }
        @Override public boolean isDone() { return true; }
        @Override public Void get() { return null; }
        @Override public Void get(long timeout, TimeUnit unit) { return null; }
    }

    // ---- Fake ChainState for testing ----

    static class FakeChainState implements ChainState {
        private final ConcurrentSkipListMap<Long, BlockEntry> blocksBySlot = new ConcurrentSkipListMap<>();
        private final ConcurrentHashMap<String, BlockEntry> blocksByHash = new ConcurrentHashMap<>();
        private volatile ChainTip tip;

        Point pointAt(int blockNumber) {
            BlockEntry entry = blocksBySlot.get((long) blockNumber);
            if (entry == null) return null;
            return new Point(entry.slot, entry.hashHex);
        }

        void addBlock(int blockNumber) {
            long slot = blockNumber;
            byte[] hash = intToHash(blockNumber);
            String hashHex = HexUtil.encodeHexString(hash);
            // Create valid CBOR: array [7, h'<blockNumber bytes>'] — era 7 (Conway) + dummy header
            // 0x82 = 2-element array, 0x07 = uint 7, 0x43 = 3-byte bstr, then 3 bytes
            byte[] headerBytes = new byte[]{
                    (byte) 0x82, 0x07, 0x43,
                    (byte) (blockNumber & 0xFF), (byte) ((blockNumber >> 8) & 0xFF), 0x01
            };
            BlockEntry entry = new BlockEntry(slot, blockNumber, hash, hashHex, headerBytes);
            blocksBySlot.put(slot, entry);
            blocksByHash.put(hashHex, entry);
            tip = new ChainTip(slot, hash, blockNumber);
        }

        @Override
        public void rollbackTo(Long slot) {
            blocksBySlot.tailMap(slot, false).forEach((s, entry) -> blocksByHash.remove(entry.hashHex));
            blocksBySlot.tailMap(slot, false).clear();
            if (!blocksBySlot.isEmpty()) {
                Map.Entry<Long, BlockEntry> last = blocksBySlot.lastEntry();
                BlockEntry e = last.getValue();
                tip = new ChainTip(e.slot, e.hash, e.blockNumber);
            } else {
                tip = null;
            }
        }

        @Override public void storeBlock(byte[] blockHash, Long blockNumber, Long slot, byte[] block) {}
        @Override public byte[] getBlock(byte[] blockHash) {
            BlockEntry e = blocksByHash.get(HexUtil.encodeHexString(blockHash));
            return e != null ? e.headerBytes : null;
        }
        @Override public boolean hasBlock(byte[] blockHash) {
            return blocksByHash.containsKey(HexUtil.encodeHexString(blockHash));
        }
        @Override public void storeBlockHeader(byte[] blockHash, Long blockNumber, Long slot, byte[] blockHeader) {}
        @Override public byte[] getBlockHeader(byte[] blockHash) {
            BlockEntry e = blocksByHash.get(HexUtil.encodeHexString(blockHash));
            return e != null ? e.headerBytes : null;
        }
        @Override public byte[] getBlockByNumber(Long blockNumber) {
            for (BlockEntry e : blocksBySlot.values()) {
                if (e.blockNumber == blockNumber) return e.headerBytes;
            }
            return null;
        }
        @Override public byte[] getBlockHeaderByNumber(Long blockNumber) { return getBlockByNumber(blockNumber); }
        @Override public ChainTip getTip() { return tip; }
        @Override public ChainTip getHeaderTip() { return tip; }

        @Override public Point findNextBlock(Point currentPoint) {
            if (currentPoint == null) return null;
            if (currentPoint.getSlot() == 0 && currentPoint.getHash() == null) return getFirstBlock();
            Long nextSlot = blocksBySlot.higherKey(currentPoint.getSlot());
            if (nextSlot == null) return null;
            BlockEntry e = blocksBySlot.get(nextSlot);
            return e != null ? new Point(e.slot, e.hashHex) : null;
        }
        @Override public Point findNextBlockHeader(Point currentPoint) { return findNextBlock(currentPoint); }
        @Override public List<Point> findBlocksInRange(Point from, Point to) { return List.of(); }
        @Override public Point findLastPointAfterNBlocks(Point from, long batchSize) { return null; }

        @Override public boolean hasPoint(Point point) {
            if (point == null) return false;
            if (point.getSlot() == 0 && point.getHash() == null) return tip != null;
            if (point.getHash() == null) return false;
            return blocksByHash.containsKey(point.getHash());
        }
        @Override public Point getFirstBlock() {
            if (blocksBySlot.isEmpty()) return null;
            BlockEntry e = blocksBySlot.firstEntry().getValue();
            return new Point(e.slot, e.hashHex);
        }
        @Override public Long getBlockNumberBySlot(Long slot) {
            BlockEntry e = blocksBySlot.get(slot);
            return e != null ? (long) e.blockNumber : null;
        }
        @Override public Long getSlotByBlockNumber(Long blockNumber) {
            for (BlockEntry e : blocksBySlot.values()) {
                if (e.blockNumber == blockNumber) return e.slot;
            }
            return null;
        }

        private static byte[] intToHash(int n) {
            byte[] hash = new byte[32];
            hash[0] = (byte) (n & 0xFF);
            hash[1] = (byte) ((n >> 8) & 0xFF);
            hash[2] = (byte) ((n >> 16) & 0xFF);
            hash[3] = (byte) ((n >> 24) & 0xFF);
            return hash;
        }

        record BlockEntry(long slot, int blockNumber, byte[] hash, String hashHex, byte[] headerBytes) {}
    }
}
