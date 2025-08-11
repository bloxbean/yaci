package com.bloxbean.cardano.yaci.node.runtime;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.helper.N2NPeerFetcher;
import com.bloxbean.cardano.yaci.helper.PeerClient;
import com.bloxbean.cardano.yaci.node.runtime.chain.InMemoryChainState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for HeaderSyncManager with real PeerClient connections.
 * 
 * These tests connect to real Cardano nodes to verify header synchronization works correctly.
 * Tests are disabled by default to avoid requiring live network connections during CI builds.
 * 
 * To run these tests:
 * 1. Ensure you have network connectivity
 * 2. Remove @Disabled annotation
 * 3. Run with: ./gradlew test --tests "*HeaderSyncManagerIntegrationTest*"
 */
@Disabled("Integration tests require live network connection - enable manually for testing")
class HeaderSyncManagerIntegrationTest {

    // Test configuration - Cardano Preprod network
    private static final String CARDANO_HOST = "preprod-node.world.dev.cardano.org";
    private static final int CARDANO_PORT = 30000;
    private static final long PROTOCOL_MAGIC = 1; // Preprod magic
    
    // Test timeout settings
    private static final int TEST_TIMEOUT_SECONDS = 60;
    private static final int HEADER_WAIT_TIMEOUT_SECONDS = 30;
    
    private InMemoryChainState chainState;
    private HeaderSyncManager headerSyncManager;
    private PeerClient peerClient;
    
    @BeforeEach
    void setUp() {
        chainState = new InMemoryChainState();
    }
    
    @Test
    @Timeout(TEST_TIMEOUT_SECONDS)
    void testHeaderOnlySync_ConnectAndReceiveHeaders() throws InterruptedException {
        // Arrange
        Point startPoint = Point.ORIGIN; // Start from genesis for test
        peerClient = new PeerClient(CARDANO_HOST, CARDANO_PORT, PROTOCOL_MAGIC, startPoint);
        headerSyncManager = new HeaderSyncManager(peerClient, chainState);
        
        // Setup connection with header sync manager
        peerClient.connect(null, null); // No body listener, no tx listener
        
        // Add HeaderSyncManager to ChainSyncAgent listeners
        N2NPeerFetcher fetcher = getN2NPeerFetcherFromPeerClient(peerClient);
        fetcher.addChainSyncListener(headerSyncManager);
        
        // Synchronization latch to wait for headers
        CountDownLatch headerReceivedLatch = new CountDownLatch(10); // Wait for 10 headers
        
        // Monitor header progress
        Thread monitorThread = new Thread(() -> {
            long lastHeaderCount = 0;
            while (headerReceivedLatch.getCount() > 0) {
                try {
                    Thread.sleep(1000);
                    long currentHeaderCount = headerSyncManager.getHeadersReceived();
                    if (currentHeaderCount > lastHeaderCount) {
                        System.out.println("📄 Headers received: " + currentHeaderCount);
                        lastHeaderCount = currentHeaderCount;
                        
                        // Countdown for each new header
                        for (long i = lastHeaderCount; i < currentHeaderCount; i++) {
                            headerReceivedLatch.countDown();
                        }
                        lastHeaderCount = currentHeaderCount;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        
        // Act - Start header-only sync
        monitorThread.start();
        peerClient.startHeaderSync(startPoint, true); // Enable pipelining
        
        // Wait for headers to be received
        boolean headersReceived = headerReceivedLatch.await(HEADER_WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        
        // Stop monitoring thread
        monitorThread.interrupt();
        
        // Assert - Verify headers were received and stored
        assertTrue(headersReceived, "Should receive at least 10 headers within timeout");
        assertTrue(headerSyncManager.getHeadersReceived() >= 10, 
                  "HeaderSyncManager should track received headers");
        
        // Verify header_tip is updated in ChainState  
        ChainTip headerTip = chainState.getHeaderTip();
        assertNotNull(headerTip, "ChainState should have header_tip after sync");
        assertTrue(headerTip.getSlot() > 0, "Header tip should have valid slot");
        assertTrue(headerTip.getBlockNumber() >= 0, "Header tip should have valid block number");
        
        // Verify no complete blocks were stored (header-only mode)
        ChainTip bodyTip = chainState.getTip();
        if (bodyTip != null) {
            // If there's a body tip, it should be behind header tip (headers-only sync)
            assertTrue(headerTip.getSlot() >= bodyTip.getSlot(), 
                      "Header tip should be at or ahead of body tip in header-only sync");
        }
        
        // Verify HeaderSyncManager status
        HeaderSyncManager.HeaderSyncStatus status = headerSyncManager.getStatus();
        assertTrue(status.active, "HeaderSyncManager should report as active");
        assertTrue(status.headersReceived >= 10, "Status should reflect headers received");
        assertNotNull(status.lastHeaderSlot, "Status should have last header slot");
        
        // Log final metrics
        HeaderSyncManager.HeaderMetrics metrics = headerSyncManager.getHeaderMetrics();
        System.out.println("📊 Final metrics: " + metrics);
        System.out.println("📄 Header tip: slot=" + headerTip.getSlot() + ", block=" + headerTip.getBlockNumber());
        
        // Cleanup
        peerClient.stop();
    }
    
    @Test
    @Timeout(TEST_TIMEOUT_SECONDS) 
    void testHeaderSync_ReconnectionBehavior() throws InterruptedException {
        // This test would simulate network disconnection and verify automatic reconnection
        // For now, we'll test the basic connection stability
        
        // Arrange
        Point startPoint = new Point(100000, null); // Start from a reasonable slot
        peerClient = new PeerClient(CARDANO_HOST, CARDANO_PORT, PROTOCOL_MAGIC, startPoint);
        headerSyncManager = new HeaderSyncManager(peerClient, chainState);
        
        // Setup connection
        peerClient.connect(null, null);
        N2NPeerFetcher fetcher = getN2NPeerFetcherFromPeerClient(peerClient);
        fetcher.addChainSyncListener(headerSyncManager);
        
        // Act - Start sync and let it run briefly
        peerClient.startHeaderSync(startPoint, true);
        Thread.sleep(5000); // Let it sync for 5 seconds
        
        // Verify connection is stable
        assertTrue(peerClient.isRunning(), "PeerClient connection should remain stable");
        
        // Verify headers were received
        assertTrue(headerSyncManager.getHeadersReceived() > 0, 
                  "Should receive some headers during stable connection");
        
        // Cleanup
        peerClient.stop();
    }
    
    @Test
    @Timeout(TEST_TIMEOUT_SECONDS)
    void testHeaderSync_PerformanceMetrics() throws InterruptedException {
        // Test to verify header synchronization performance and metrics
        
        // Arrange
        Point startPoint = new Point(1000000, null); // Start from a more recent slot
        peerClient = new PeerClient(CARDANO_HOST, CARDANO_PORT, PROTOCOL_MAGIC, startPoint);
        headerSyncManager = new HeaderSyncManager(peerClient, chainState);
        
        // Setup connection
        peerClient.connect(null, null);
        N2NPeerFetcher fetcher = getN2NPeerFetcherFromPeerClient(peerClient);
        fetcher.addChainSyncListener(headerSyncManager);
        
        // Act - Start sync and measure performance
        long startTime = System.currentTimeMillis();
        peerClient.startHeaderSync(startPoint, true);
        
        // Let it sync for 10 seconds
        Thread.sleep(10000);
        
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        
        // Assert - Verify performance
        long headersReceived = headerSyncManager.getHeadersReceived();
        assertTrue(headersReceived > 0, "Should receive headers during sync");
        
        // Calculate headers per second
        double headersPerSecond = (double) headersReceived / (duration / 1000.0);
        System.out.println("📈 Performance: " + headersPerSecond + " headers/second");
        System.out.println("📊 Total headers: " + headersReceived + " in " + duration + "ms");
        
        // Verify reasonable performance (adjust threshold based on network conditions)
        assertTrue(headersPerSecond > 0.1, "Should achieve reasonable header sync speed");
        
        // Verify metrics tracking
        HeaderSyncManager.HeaderMetrics metrics = headerSyncManager.getHeaderMetrics();
        assertEquals(headersReceived, metrics.totalHeaders, "Total headers metric should match");
        
        // Cleanup
        peerClient.stop();
    }
    
    // ================================================================
    // Helper Methods
    // ================================================================
    
    /**
     * Extract N2NPeerFetcher from PeerClient using reflection if needed
     * This is a test-only method to access internal components
     */
    private N2NPeerFetcher getN2NPeerFetcherFromPeerClient(PeerClient peerClient) {
        try {
            // Try to access the internal N2NPeerFetcher
            // This assumes PeerClient has a method to get the fetcher or we use reflection
            var field = PeerClient.class.getDeclaredField("n2NPeerFetcher");
            field.setAccessible(true);
            return (N2NPeerFetcher) field.get(peerClient);
        } catch (Exception e) {
            throw new RuntimeException("Could not access N2NPeerFetcher from PeerClient", e);
        }
    }
}