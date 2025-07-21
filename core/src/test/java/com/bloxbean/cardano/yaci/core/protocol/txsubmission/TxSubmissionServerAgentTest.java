package com.bloxbean.cardano.yaci.core.protocol.txsubmission;

import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TxSubmissionServerAgentTest {

    private TxSubmissionServerAgent agent;

    @BeforeEach
    void setUp() {
        agent = new TxSubmissionServerAgent();
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
        
        // Create a ReplyTxIds message
        ReplyTxIds replyTxIds = new ReplyTxIds();
        replyTxIds.addTxId("tx1", 100);
        replyTxIds.addTxId("tx2", 200);
        
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
        assertTrue(agent.hasReceivedTxId("tx1"));
        assertTrue(agent.hasReceivedTxId("tx2"));
        assertFalse(agent.hasReceivedTxId("tx3"));
        
        // Verify listener was called
        assertNotNull(receivedReply.get());
        assertEquals(2, receivedReply.get().getTxIdAndSizeMap().size());
    }

    @Test
    void testReplyTxsProcessing() {
        // Move to Idle state
        agent.receiveResponse(new Init());
        
        // Create a ReplyTxs message
        ReplyTxs replyTxs = new ReplyTxs();
        replyTxs.addTx(new byte[]{1, 2, 3});
        replyTxs.addTx(new byte[]{4, 5, 6});
        
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
        
        // Verify listener was called
        assertNotNull(receivedReply.get());
        assertEquals(2, receivedReply.get().getTxns().size());
    }

    @Test
    void testBuildNextMessageWithPendingRequest() {
        // Move to Idle state
        agent.receiveResponse(new Init());
        
        // Initially no message to send
        assertNull(agent.buildNextMessage());
        
        // Request some transaction IDs
        agent.requestTxIds((short) 0, (short) 5, false);
        
        // Should return the pending RequestTxIds message
        var message = agent.buildNextMessage();
        assertNotNull(message);
        assertInstanceOf(RequestTxIds.class, message);
        
        RequestTxIds requestTxIds = (RequestTxIds) message;
        assertFalse(requestTxIds.isBlocking());
        assertEquals(0, requestTxIds.getAckTxIds());
        assertEquals(5, requestTxIds.getReqTxIds());
        
        // After building the message once, it should be cleared
        assertNull(agent.buildNextMessage());
    }

    @Test
    void testRequestTxsMethod() {
        // Move to Idle state
        agent.receiveResponse(new Init());
        
        // Request specific transactions
        agent.requestTxs(Arrays.asList("tx1", "tx2", "tx3"));
        
        // Should return the pending RequestTxs message
        var message = agent.buildNextMessage();
        assertNotNull(message);
        assertInstanceOf(RequestTxs.class, message);
        
        RequestTxs requestTxs = (RequestTxs) message;
        assertEquals(3, requestTxs.getTxIds().size());
        assertTrue(requestTxs.getTxIds().contains("tx1"));
        assertTrue(requestTxs.getTxIds().contains("tx2"));
        assertTrue(requestTxs.getTxIds().contains("tx3"));
    }

    @Test
    void testCannotRequestInWrongState() {
        // In Init state, server doesn't have agency
        assertFalse(agent.hasAgency());
        
        // Should not be able to request transaction IDs
        agent.requestTxIds((short) 0, (short) 5, false);
        assertNull(agent.buildNextMessage());
        
        // Should not be able to request transactions
        agent.requestTxs(Arrays.asList("tx1"));
        assertNull(agent.buildNextMessage());
    }

    @Test
    void testReset() {
        // Add some data and move to Idle state
        agent.receiveResponse(new Init());
        
        ReplyTxIds replyTxIds = new ReplyTxIds();
        replyTxIds.addTxId("tx1", 100);
        agent.receiveResponse(replyTxIds);
        
        agent.requestTxIds((short) 0, (short) 5, false);
        
        // Verify we have data
        assertEquals(1, agent.getReceivedTxIdCount());
        assertNotNull(agent.buildNextMessage());
        
        // Reset should clear everything
        agent.reset();
        
        assertEquals(TxSubmissionState.Init, agent.getCurrentState());
        assertEquals(0, agent.getReceivedTxIdCount());
        assertNull(agent.buildNextMessage());
    }
}