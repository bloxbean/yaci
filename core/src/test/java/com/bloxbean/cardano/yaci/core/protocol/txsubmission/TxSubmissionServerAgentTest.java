package com.bloxbean.cardano.yaci.core.protocol.txsubmission;

import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TxSubmissionServerAgentTest {

    private TxSubmissionServerAgent agent;

    @BeforeEach
    void setUp() {
        // Use default config for testing
        TxSubmissionConfig config = TxSubmissionConfig.createDefault();
        agent = new TxSubmissionServerAgent(config);
    }

    @AfterEach
    void tearDown() {
        if (agent != null) {
            agent.shutdown();
        }
    }

    @Test
    void testInitialState() {
        assertEquals(TxSubmissionState.Init, agent.getCurrentState());
        assertEquals(4, agent.getProtocolId());
        assertFalse(agent.isDone());
        assertEquals(0, agent.getReceivedTxIdCount());
    }

    @Test
    void testServerAgencyInIdleState() {
        // Start in Init state - client has agency
        assertFalse(agent.hasAgency());

        // Process Init message to move to Idle state
        agent.receiveResponse(new Init());
        assertEquals(TxSubmissionState.Idle, agent.getCurrentState());

        // In Idle state, server has agency
        assertTrue(agent.hasAgency());
    }

    @Test
    void testReplyTxIdsProcessing() {
        // Move to Idle state
        agent.receiveResponse(new Init());

        // Create a ReplyTxIds message with TxId objects
        ReplyTxIds replyTxIds = new ReplyTxIds();
        TxId txId1 = new TxId(com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era.Conway, "tx1".getBytes());
        TxId txId2 = new TxId(com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era.Conway, "tx2".getBytes());
        replyTxIds.addTxId(txId1, 100);
        replyTxIds.addTxId(txId2, 200);

        // Track if listener was called
        AtomicReference<ReplyTxIds> receivedReply = new AtomicReference<>();
        agent.addListener(new TxSubmissionListener() {
            @Override
            public void handleRequestTxs(RequestTxs requestTxs) {}
            @Override
            public void handleRequestTxIdsNonBlocking(RequestTxIds requestTxIds) {}
            @Override
            public void handleRequestTxIdsBlocking(RequestTxIds requestTxIds) {}
            @Override
            public void handleReplyTxIds(ReplyTxIds replyTxIds) {
                receivedReply.set(replyTxIds);
            }
        });

        // Process ReplyTxIds message
        agent.receiveResponse(replyTxIds);

        // Verify state and tracking
        assertEquals(TxSubmissionState.Idle, agent.getCurrentState());
        assertEquals(2, agent.getReceivedTxIdCount());
        assertEquals(2, agent.getOutstandingTxCount()); // Should be in queue

        // TxId.toString() returns hex string of the bytes
        assertTrue(agent.hasReceivedTxId(com.bloxbean.cardano.yaci.core.util.HexUtil.encodeHexString("tx1".getBytes())));
        assertTrue(agent.hasReceivedTxId(com.bloxbean.cardano.yaci.core.util.HexUtil.encodeHexString("tx2".getBytes())));
        assertFalse(agent.hasReceivedTxId("tx3"));

        // Verify listener was called
        assertNotNull(receivedReply.get());
        assertEquals(2, receivedReply.get().getTxIdAndSizeMap().size());
    }

    @Test
    void testReplyTxsProcessing() {
        // Move to Idle state
        agent.receiveResponse(new Init());

        // First add some transaction IDs to the queue
        ReplyTxIds replyTxIds = new ReplyTxIds();
        TxId txId1 = new TxId(com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era.Conway, "tx1".getBytes());
        TxId txId2 = new TxId(com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era.Conway, "tx2".getBytes());
        replyTxIds.addTxId(txId1, 100);
        replyTxIds.addTxId(txId2, 200);
        agent.receiveResponse(replyTxIds);

        // Now process the transactions
        ReplyTxs replyTxs = new ReplyTxs();
        Tx tx1 = new Tx(com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era.Conway, new byte[]{1, 2, 3});
        Tx tx2 = new Tx(com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era.Conway, new byte[]{4, 5, 6});
        replyTxs.addTx(tx1);
        replyTxs.addTx(tx2);

        // Track if listener was called
        AtomicReference<ReplyTxs> receivedReply = new AtomicReference<>();
        agent.addListener(new TxSubmissionListener() {
            @Override
            public void handleRequestTxs(RequestTxs requestTxs) {}
            @Override
            public void handleRequestTxIdsNonBlocking(RequestTxIds requestTxIds) {}
            @Override
            public void handleRequestTxIdsBlocking(RequestTxIds requestTxIds) {}
            @Override
            public void handleReplyTxs(ReplyTxs replyTxs) {
                receivedReply.set(replyTxs);
            }
        });

        // Process ReplyTxs message
        agent.receiveResponse(replyTxs);

        // Verify state
        assertEquals(TxSubmissionState.Idle, agent.getCurrentState());

        // Queue should be empty after processing
        assertEquals(0, agent.getOutstandingTxCount());

        // After processing all transactions, the agent sends next blocking request
        // which resets acknowledgments to 0, so pending acknowledgments should be 0
        assertEquals(0, agent.getPendingAcknowledgments());

        // Verify listener was called
        assertNotNull(receivedReply.get());
        assertEquals(2, receivedReply.get().getTxns().size());

        // Should have a new blocking request ready with acknowledgments
        var nextMessage = agent.buildNextMessage();
        assertNotNull(nextMessage);
        assertInstanceOf(RequestTxIds.class, nextMessage);
        RequestTxIds nextRequest = (RequestTxIds) nextMessage;
        assertEquals(2, nextRequest.getAckTxIds()); // Acknowledgments are sent here
    }

    @Test
    void testBuildNextMessageWithPendingRequest() {
        // Move to Idle state - this should trigger initial blocking request
        agent.receiveResponse(new Init());

        // Should have a pending blocking request after Init
        var message = agent.buildNextMessage();
        assertNotNull(message);
        assertInstanceOf(RequestTxIds.class, message);

        RequestTxIds requestTxIds = (RequestTxIds) message;
        assertTrue(requestTxIds.isBlocking()); // Should be blocking
        assertEquals(0, requestTxIds.getAckTxIds());
        assertEquals(10, requestTxIds.getReqTxIds()); // Default batch size

        // After building the message once, it should be cleared
        assertNull(agent.buildNextMessage());
    }

    @Test
    void testRequestTxsAfterReplyTxIds() {
        // Move to Idle state
        agent.receiveResponse(new Init());

        // Clear the initial blocking request
        agent.buildNextMessage();

        // Simulate receiving transaction IDs
        ReplyTxIds replyTxIds = new ReplyTxIds();
        TxId txId1 = new TxId(com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era.Conway, "tx1".getBytes());
        TxId txId2 = new TxId(com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era.Conway, "tx2".getBytes());
        TxId txId3 = new TxId(com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era.Conway, "tx3".getBytes());
        replyTxIds.addTxId(txId1, 100);
        replyTxIds.addTxId(txId2, 200);
        replyTxIds.addTxId(txId3, 300);

        agent.receiveResponse(replyTxIds);

        // Should have a pending RequestTxs message
        var message = agent.buildNextMessage();
        assertNotNull(message);
        assertInstanceOf(RequestTxs.class, message);

        RequestTxs requestTxs = (RequestTxs) message;
        assertEquals(3, requestTxs.getTxIds().size());
    }

    @Test
    void testCannotRequestInWrongState() {
        // In Init state, server doesn't have agency
        assertFalse(agent.hasAgency());

        // No pending request should be created in Init state
        assertNull(agent.buildNextMessage());
    }

    @Test
    void testReset() {
        // Add some data and move to Idle state
        agent.receiveResponse(new Init());

        // Clear initial request
        agent.buildNextMessage();

        ReplyTxIds replyTxIds = new ReplyTxIds();
        TxId txId1 = new TxId(com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era.Conway, "tx1".getBytes());
        replyTxIds.addTxId(txId1, 100);
        agent.receiveResponse(replyTxIds);

        // Verify we have data
        assertEquals(1, agent.getReceivedTxIdCount());
        assertEquals(1, agent.getOutstandingTxCount());

        // Reset should clear everything
        agent.reset();

        assertEquals(TxSubmissionState.Init, agent.getCurrentState());
        assertEquals(0, agent.getReceivedTxIdCount());
        assertEquals(0, agent.getOutstandingTxCount());
        assertEquals(0, agent.getPendingAcknowledgments());
        assertNull(agent.buildNextMessage());
    }

    @Test
    void testConfigurationMethods() {
        TxSubmissionConfig config = agent.getConfig();
        assertNotNull(config);
        assertFalse(config.isPeriodicRequestsEnabled()); // No periodic requests in blocking mode
        assertTrue(config.isUseBlockingMode()); // Always blocking
        assertEquals(10, config.getBatchSize()); // Default batch size
    }

    @Test
    void testBlockingRequestFlow() {
        // Move to Idle state
        agent.receiveResponse(new Init());

        // Should have initial blocking request
        var msg1 = agent.buildNextMessage();
        assertNotNull(msg1);
        assertInstanceOf(RequestTxIds.class, msg1);
        RequestTxIds req1 = (RequestTxIds) msg1;
        assertTrue(req1.isBlocking());
        assertEquals(0, req1.getAckTxIds()); // No acks on first request

        // Simulate receiving transaction IDs
        ReplyTxIds replyTxIds = new ReplyTxIds();
        TxId txId1 = new TxId(com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era.Conway, "tx1".getBytes());
        TxId txId2 = new TxId(com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era.Conway, "tx2".getBytes());
        replyTxIds.addTxId(txId1, 100);
        replyTxIds.addTxId(txId2, 200);
        agent.receiveResponse(replyTxIds);

        // Should request transaction bodies
        var msg2 = agent.buildNextMessage();
        assertNotNull(msg2);
        assertInstanceOf(RequestTxs.class, msg2);

        // Simulate receiving transactions
        ReplyTxs replyTxs = new ReplyTxs();
        Tx tx1 = new Tx(com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era.Conway, new byte[]{1, 2, 3});
        Tx tx2 = new Tx(com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era.Conway, new byte[]{4, 5, 6});
        replyTxs.addTx(tx1);
        replyTxs.addTx(tx2);
        agent.receiveResponse(replyTxs);

        // Should have next blocking request with acknowledgments
        var msg3 = agent.buildNextMessage();
        assertNotNull(msg3);
        assertInstanceOf(RequestTxIds.class, msg3);
        RequestTxIds req2 = (RequestTxIds) msg3;
        assertTrue(req2.isBlocking());
        assertEquals(2, req2.getAckTxIds()); // Should acknowledge 2 transactions
    }

    @Test
    void testShutdown() {
        // Create agent
        TxSubmissionConfig config = TxSubmissionConfig.createDefault();
        TxSubmissionServerAgent testAgent = new TxSubmissionServerAgent(config);

        // Move to Idle state
        testAgent.receiveResponse(new Init());

        // Add some data
        ReplyTxIds replyTxIds = new ReplyTxIds();
        TxId txId1 = new TxId(com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era.Conway, "tx1".getBytes());
        replyTxIds.addTxId(txId1, 100);
        testAgent.receiveResponse(replyTxIds);

        // Shutdown should clean up everything
        testAgent.shutdown();

        assertEquals(TxSubmissionState.Init, testAgent.getCurrentState());
        assertEquals(0, testAgent.getReceivedTxIdCount());
        assertEquals(0, testAgent.getOutstandingTxCount());
    }

    @Test
    void testEmptyReplyTxIds() {
        // Move to Idle state
        agent.receiveResponse(new Init());

        // Clear initial request
        agent.buildNextMessage();

        // Simulate receiving empty ReplyTxIds
        ReplyTxIds emptyReply = new ReplyTxIds();
        agent.receiveResponse(emptyReply);

        // Should immediately send next blocking request
        var message = agent.buildNextMessage();
        assertNotNull(message);
        assertInstanceOf(RequestTxIds.class, message);
        RequestTxIds requestTxIds = (RequestTxIds) message;
        assertTrue(requestTxIds.isBlocking());
        assertEquals(0, requestTxIds.getAckTxIds()); // No transactions to acknowledge
    }

    @Test
    void testConfigurationValidation() {
        // Valid config should not log warnings
        TxSubmissionConfig validConfig = TxSubmissionConfig.createDefault();
        validConfig.validate(); // Should not throw or log warnings

        // Test invalid batch size
        TxSubmissionConfig invalidConfig = TxSubmissionConfig.builder()
                .batchSize(0)
                .build();
        invalidConfig.validate(); // Should log warning

        // Test batch size exceeding protocol limit
        TxSubmissionConfig largeBatchConfig = TxSubmissionConfig.builder()
                .batchSize(15) // Exceeds protocol limit of 10
                .build();
        largeBatchConfig.validate(); // Should log warning
    }
}
