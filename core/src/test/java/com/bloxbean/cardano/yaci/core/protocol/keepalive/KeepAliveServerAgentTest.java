package com.bloxbean.cardano.yaci.core.protocol.keepalive;

import com.bloxbean.cardano.yaci.core.protocol.keepalive.messages.MsgKeepAlive;
import com.bloxbean.cardano.yaci.core.protocol.keepalive.messages.MsgKeepAliveResponse;
import com.bloxbean.cardano.yaci.core.protocol.keepalive.messages.MsgDone;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class KeepAliveServerAgentTest {

    private KeepAliveServerAgent serverAgent;
    private AtomicInteger responseCount;
    private AtomicBoolean responseReceived;

    @BeforeEach
    void setUp() {
        serverAgent = new KeepAliveServerAgent();
        responseCount = new AtomicInteger(0);
        responseReceived = new AtomicBoolean(false);

        // Add a listener to track responses
        serverAgent.addListener(new KeepAliveListener() {
            @Override
            public void keepAliveResponse(MsgKeepAliveResponse response) {
                responseCount.incrementAndGet();
                responseReceived.set(true);
            }
        });
    }

    @Test
    void testKeepAliveServerAgent_InitialState() {
        assertEquals(8, serverAgent.getProtocolId());
        assertFalse(serverAgent.isDone());
        assertEquals(KeepAliveState.Client, serverAgent.getCurrentState());
    }

    @Test
    void testKeepAliveServerAgent_HandleKeepAlive() {
        int testCookie = 12345;
        MsgKeepAlive keepAlive = new MsgKeepAlive(testCookie);

        // Process the keep alive message
        serverAgent.processResponse(keepAlive);

        // Should have a pending response
        MsgKeepAliveResponse response = (MsgKeepAliveResponse) serverAgent.buildNextMessage();
        assertNotNull(response);
        assertEquals(testCookie, response.getCookie());

        // Listener should have been notified
        assertTrue(responseReceived.get());
        assertEquals(1, responseCount.get());
    }

    @Test
    void testKeepAliveServerAgent_HandleMultipleKeepAlives() {
        int[] testCookies = {111, 222, 333};

        for (int cookie : testCookies) {
            MsgKeepAlive keepAlive = new MsgKeepAlive(cookie);
            serverAgent.processResponse(keepAlive);

            MsgKeepAliveResponse response = (MsgKeepAliveResponse) serverAgent.buildNextMessage();
            assertNotNull(response);
            assertEquals(cookie, response.getCookie());
        }

        // Should have received all responses
        assertEquals(testCookies.length, responseCount.get());
    }

    @Test
    void testKeepAliveServerAgent_HandleDone() {
        MsgDone done = new MsgDone();

        // Process the done message
        serverAgent.processResponse(done);

        // Should transition to Done state
        assertTrue(serverAgent.isDone());
        assertEquals(KeepAliveState.Done, serverAgent.getCurrentState());

        // Should have a Done message as response
        MsgDone doneResponse = (MsgDone) serverAgent.buildNextMessage();
        assertNotNull(doneResponse);
    }

    @Test
    void testKeepAliveServerAgent_Shutdown() {
        assertFalse(serverAgent.isDone());

        serverAgent.shutdown();

        // Should return MsgDone when building next message
        MsgDone done = (MsgDone) serverAgent.buildNextMessage();
        assertNotNull(done);
    }

    @Test
    void testKeepAliveServerAgent_Reset() {
        // Process a keep alive to set some state
        serverAgent.processResponse(new MsgKeepAlive(123));

        // Reset the agent
        serverAgent.reset();

        // Should be back to initial state
        assertEquals(KeepAliveState.Server, serverAgent.getCurrentState());
        assertFalse(serverAgent.isDone());

        // Should have no pending response
        assertNull(serverAgent.buildNextMessage());
    }

    @Test
    void testKeepAliveServerAgent_EchoResponses() {
        // Test that server echoes back the exact same cookie values
        int[] testCookies = {0, 1, 65535, 12345, 99999};

        for (int cookie : testCookies) {
            MsgKeepAlive keepAlive = new MsgKeepAlive(cookie);
            serverAgent.processResponse(keepAlive);

            MsgKeepAliveResponse response = (MsgKeepAliveResponse) serverAgent.buildNextMessage();
            assertNotNull(response, "Response should not be null for cookie " + cookie);
            assertEquals(cookie, response.getCookie(), "Cookie should be echoed back exactly");
        }
    }
}
