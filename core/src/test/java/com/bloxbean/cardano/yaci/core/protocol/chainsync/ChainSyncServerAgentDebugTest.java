package com.bloxbean.cardano.yaci.core.protocol.chainsync;

import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.*;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainSyncServerAgent;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainSyncState;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Disabled
public class ChainSyncServerAgentDebugTest {

    private ChainSyncServerAgent serverAgent;
    private ChainState mockChainState;
    private Channel mockChannel;

    @BeforeEach
    public void setup() {
        mockChainState = mock(ChainState.class);
        mockChannel = mock(Channel.class);
        serverAgent = new ChainSyncServerAgent(mockChainState);
        serverAgent.setChannel(mockChannel);

        // Setup channel mock to succeed
        ChannelFuture mockFuture = mock(ChannelFuture.class);
        when(mockChannel.writeAndFlush(any())).thenReturn(mockFuture);
        when(mockFuture.addListener(any())).thenAnswer(invocation -> {
            // Simulate successful write
            io.netty.util.concurrent.GenericFutureListener listener = invocation.getArgument(0);
            when(mockFuture.isSuccess()).thenReturn(true);
            listener.operationComplete(mockFuture);
            return mockFuture;
        });
    }

    @Test
    public void testStateTransitionAfterIntersectFound() throws Exception {
        // Setup chain state
        ChainTip mockTip = new ChainTip(100L, HexUtil.decodeHexString("abcd"), 100L);
        when(mockChainState.getTip()).thenReturn(mockTip);
        when(mockChainState.hasPoint(any())).thenReturn(true);

        // Test Point.ORIGIN
        Point origin = new Point(0, null);
        Point firstBlock = new Point(1, "1234");

        when(mockChainState.findNextBlock(origin)).thenReturn(firstBlock);
        when(mockChainState.getBlockHeader(any())).thenReturn(new byte[]{1, 2, 3, 4});

        // Step 1: Client sends FindIntersect (State: Idle -> Intersect)
        System.out.println("Initial state: " + serverAgent.getCurrentState());
        assertEquals(ChainSyncState.Idle, serverAgent.getCurrentState());

        FindIntersect findIntersect = new FindIntersect(new Point[]{origin});

        // Simulate receiving FindIntersect
        State nextState = serverAgent.getCurrentState().nextState(findIntersect);
        System.out.println("After FindIntersect, next state should be: " + nextState);
        assertEquals(ChainSyncState.Intersect, nextState);

        // Process the message
        serverAgent.processResponse(findIntersect);

        // Check that server prepared IntersectFound response
        assertTrue(serverAgent.hasAgency(), "Server should have hasAgency in Intersect state");

        // Server sends IntersectFound (State: Intersect -> Idle)
        serverAgent.sendNextMessage();
        System.out.println("State after sending IntersectFound: " + serverAgent.getCurrentState());
        assertEquals(ChainSyncState.Idle, serverAgent.getCurrentState());

        // Step 2: Client sends RequestNext (State: Idle -> CanAwait)
        RequestNext requestNext = new RequestNext();
        nextState = serverAgent.getCurrentState().nextState(requestNext);
        System.out.println("After RequestNext, next state should be: " + nextState);
        assertEquals(ChainSyncState.CanAwait, nextState);

        // Update state in agent manually (simulating receiveResponse)
        serverAgent.receiveResponse(requestNext);
        System.out.println("State after receiving RequestNext: " + serverAgent.getCurrentState());
        assertEquals(ChainSyncState.CanAwait, serverAgent.getCurrentState());

        // Server should have agency in CanAwait state
        assertTrue(serverAgent.hasAgency(), "Server should have agency in CanAwait state");

        // Server should be able to send RollForward
        serverAgent.sendNextMessage();

        // Verify RollForward was sent
        ArgumentCaptor<Object> segmentCaptor = ArgumentCaptor.forClass(Object.class);
        verify(mockChannel, atLeastOnce()).writeAndFlush(segmentCaptor.capture());

        System.out.println("Final state: " + serverAgent.getCurrentState());
        assertEquals(ChainSyncState.Idle, serverAgent.getCurrentState(), "Should be back in Idle state after RollForward");
    }

    @Test
    public void testAgencyInStates() {
        // Test agency for each state
        assertTrue(ChainSyncState.Idle.hasAgency(true), "Client has agency in Idle");
        assertFalse(ChainSyncState.Idle.hasAgency(false), "Server doesn't have agency in Idle");

        assertFalse(ChainSyncState.CanAwait.hasAgency(true), "Client doesn't have agency in CanAwait");
        assertTrue(ChainSyncState.CanAwait.hasAgency(false), "Server has agency in CanAwait");

        assertFalse(ChainSyncState.MustReply.hasAgency(true), "Client doesn't have agency in MustReply");
        assertTrue(ChainSyncState.MustReply.hasAgency(false), "Server has agency in MustReply");

        assertFalse(ChainSyncState.Intersect.hasAgency(true), "Client doesn't have agency in Intersect");
        assertTrue(ChainSyncState.Intersect.hasAgency(false), "Server has agency in Intersect");
    }

    // Helper to get current state via reflection (since it's protected)
    private State getCurrentState() {
        try {
            java.lang.reflect.Field field = serverAgent.getClass().getSuperclass().getDeclaredField("currenState");
            field.setAccessible(true);
            return (State) field.get(serverAgent);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
