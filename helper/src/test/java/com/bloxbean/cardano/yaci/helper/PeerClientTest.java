package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.helper.model.Transaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@Disabled
class PeerClientTest {

    private TcpProxyManager proxyManager;
    private static final int PROXY_PORT = 13001;
    private static final int TEST_DURATION_SECONDS = 60;
    private static final int RECONNECT_CYCLES = 3;

    @BeforeEach
    void setUp() throws IOException {
        proxyManager = new TcpProxyManager();
        proxyManager.startProxy(PROXY_PORT, Constants.PREPROD_PUBLIC_RELAY_ADDR, Constants.PREPROD_PUBLIC_RELAY_PORT);
    }

    @AfterEach
    void tearDown() {
        if (proxyManager != null) {
            proxyManager.stopAll();
        }
    }

    @Test
    void testReconnectionWithNoDuplicatesAndNoMissingBlocks() throws InterruptedException, IOException {
        Set<Long> receivedBlockNumbers = ConcurrentHashMap.newKeySet();
        AtomicLong lastBlockNumber = new AtomicLong(-1);
        AtomicLong duplicateCount = new AtomicLong(0);
        AtomicLong missingBlockCount = new AtomicLong(0);
        CountDownLatch testCompletionLatch = new CountDownLatch(1);

        PeerClient peerClient = new PeerClient("localhost", PROXY_PORT,
                Constants.PREPROD_PROTOCOL_MAGIC, Constants.WELL_KNOWN_PREPROD_POINT);

        peerClient.connect(new BlockChainDataListener() {
            @Override
            public void onRollback(Point point) {
                System.out.println("PeerClientTest.onRollback: " + point);
            }

            @Override
            public void onBlock(Era era, Block block, List<Transaction> transactions) {
                long blockNumber = block.getHeader().getHeaderBody().getBlockNumber();
                System.out.println("Received block: " + blockNumber);

                if (!receivedBlockNumbers.add(blockNumber)) {
                    duplicateCount.incrementAndGet();
                    System.err.println("DUPLICATE BLOCK DETECTED: " + blockNumber);
                }

                long previousBlockNumber = lastBlockNumber.get();
                if (previousBlockNumber != -1 && blockNumber > previousBlockNumber + 1) {
                    long missing = blockNumber - previousBlockNumber - 1;
                    missingBlockCount.addAndGet(missing);
                    System.err.println("MISSING BLOCKS DETECTED: gap between " + previousBlockNumber + " and " + blockNumber + " (missing " + missing + " blocks)");
                }

                lastBlockNumber.set(blockNumber);
            }

            @Override
            public void onByronEbBlock(ByronEbBlock byronEbBlock) {
                System.out.println("PeerClientTest.onByronEbBlock: " + byronEbBlock.getHeader().getConsensusData().getDifficulty());
            }

            @Override
            public void onByronBlock(ByronMainBlock byronBlock) {
                System.out.println("PeerClientTest.onByronBlock: " + byronBlock.getHeader().getConsensusData().getDifficulty());
            }

            public void intersactFound(Tip tip, Point point) {
                System.out.println("PeerClientTest.intersactFound: " + tip + ", " + point);
            }

            @Override
            public void rollforward(Tip tip, BlockHeader blockHeader, byte[] originalHeaderBytes) {
                long blockNumber = blockHeader.getHeaderBody().getBlockNumber();
                System.out.println("PeerClientTest.rollforward: " + blockNumber);

                if (!receivedBlockNumbers.add(blockNumber)) {
                    duplicateCount.incrementAndGet();
                    System.err.println("DUPLICATE HEADER DETECTED: " + blockNumber);
                }

                long previousBlockNumber = lastBlockNumber.get();
                if (previousBlockNumber != -1 && blockNumber > previousBlockNumber + 1) {
                    long missing = blockNumber - previousBlockNumber - 1;
                    missingBlockCount.addAndGet(missing);
                    System.err.println("MISSING HEADERS DETECTED: gap between " + previousBlockNumber + " and " + blockNumber + " (missing " + missing + " blocks)");
                }

                lastBlockNumber.set(blockNumber);
            }

        }, null);

        peerClient.startHeaderSync(Constants.WELL_KNOWN_PREPROD_POINT, true);

        Thread reconnectionSimulator = new Thread(() -> {
            try {
                for (int cycle = 1; cycle <= RECONNECT_CYCLES; cycle++) {
                    Thread.sleep(TEST_DURATION_SECONDS * 1000 / RECONNECT_CYCLES);

                    System.out.println("=== SIMULATING DISCONNECTION - CYCLE " + cycle + " ===");
                    proxyManager.stopProxy(PROXY_PORT);

                    Thread.sleep(2000);

                    System.out.println("=== SIMULATING RECONNECTION - CYCLE " + cycle + " ===");
                    proxyManager.startProxy(PROXY_PORT, Constants.PREPROD_PUBLIC_RELAY_ADDR, Constants.PREPROD_PUBLIC_RELAY_PORT);
                }

                Thread.sleep(5000);
                testCompletionLatch.countDown();
            } catch (InterruptedException | IOException e) {
                System.err.println("Error in reconnection simulator: " + e.getMessage());
                testCompletionLatch.countDown();
            }
        });

        reconnectionSimulator.start();

        boolean testCompleted = testCompletionLatch.await(TEST_DURATION_SECONDS + 30, TimeUnit.SECONDS);

        peerClient.stop();
        reconnectionSimulator.interrupt();

        System.out.println("=== TEST RESULTS ===");
        System.out.println("Total blocks/headers received: " + receivedBlockNumbers.size());
        System.out.println("Last block number: " + lastBlockNumber.get());
        System.out.println("Duplicate count: " + duplicateCount.get());
        System.out.println("Missing block count: " + missingBlockCount.get());
        System.out.println("Test completed: " + testCompleted);

        assertTrue(testCompleted, "Test should complete within the specified time");
        assertEquals(0, duplicateCount.get(), "No duplicate blocks should be received during reconnection");
        assertEquals(0, missingBlockCount.get(), "No missing blocks should be detected during reconnection");
        assertTrue(receivedBlockNumbers.size() > 0, "Should receive at least some blocks");
    }

    @Test
    @Disabled
    void startSync() throws InterruptedException {
        PeerClient peerClient = new PeerClient(Constants.PREPROD_PUBLIC_RELAY_ADDR,
                Constants.PREPROD_PUBLIC_RELAY_PORT,
                Constants.PREPROD_PROTOCOL_MAGIC, Constants.WELL_KNOWN_PREPROD_POINT);

        AtomicLong count = new AtomicLong(0);
        peerClient.connect(new BlockChainDataListener() {
            Instant lastBlockTime = Instant.now();
            @Override
            public void onRollback(Point point) {
                    System.out.println("PeerClientTest.onRollback: " + point);
            }

            @Override
            public void onBlock(Era era, Block block, List<Transaction> transactions) {
                if (count.incrementAndGet() % 100 == 0) {
                    Instant now = Instant.now();
                    System.out.println("Received block: " + block.getHeader().getHeaderBody().getBlockNumber() +
                            ", Time taken: " + (now.toEpochMilli() - lastBlockTime.toEpochMilli()) + " ms");
                    lastBlockTime = now;
                }

            }

            @Override
            public void onByronEbBlock(ByronEbBlock byronEbBlock) {
                System.out.println("PeerClientTest.onByronEbBlock: " + byronEbBlock.getHeader().getConsensusData().getDifficulty());
            }

            @Override
            public void onByronBlock(ByronMainBlock byronBlock) {
                System.out.println("PeerClientTest.onByronBlock: " + byronBlock.getHeader().getConsensusData().getDifficulty());
            }

            public void intersactFound(Tip tip, Point point) {
                System.out.println("PeerClientTest.intersactFound: " + tip + ", " + point);
            }

            @Override
            public void rollforward(Tip tip, BlockHeader blockHeader, byte[] originalHeaderBytes) {
                if (count.incrementAndGet() % 100 == 0) {
                    Instant now = Instant.now();
                    System.out.println("Received block header: " + blockHeader.getHeaderBody().getBlockNumber() +
                            ", Time taken: " + (now.toEpochMilli() - lastBlockTime.toEpochMilli()) + " ms");
                    lastBlockTime = now;
                }
            }

        }, null);

        peerClient.startHeaderSync(Constants.WELL_KNOWN_PREPROD_POINT, true);

        while(true)
            Thread.sleep(3000);
    }
}
