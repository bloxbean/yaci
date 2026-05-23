package com.bloxbean.cardano.yaci.core.network;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.function.Supplier;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionTest {

    /**
     * Verifies that Session asks for a fresh SocketAddress on each connection attempt.
     *
     * <p>The first mocked connect attempt fails, which keeps the internal retry loop running.
     * The second attempt succeeds with a different address. This protects the DNS re-resolution
     * behavior where TCPNodeClient can resolve the configured host again before every retry.</p>
     */
    @Test
    void startResolvesSocketAddressForEachRetryAttempt() throws Exception {
        SocketAddress firstAddress = new InetSocketAddress("127.0.0.1", 3001);
        SocketAddress secondAddress = new InetSocketAddress("127.0.0.2", 3001);

        @SuppressWarnings("unchecked")
        Supplier<SocketAddress> socketAddressSupplier = mock(Supplier.class);
        when(socketAddressSupplier.get()).thenReturn(firstAddress, secondAddress);

        Bootstrap clientBootstrap = mock(Bootstrap.class);
        ChannelFuture failedConnectFuture = mock(ChannelFuture.class);
        ChannelFuture successfulConnectFuture = mock(ChannelFuture.class);
        Channel channel = mock(Channel.class);
        HandshakeAgent handshakeAgent = mock(HandshakeAgent.class);

        when(clientBootstrap.connect(firstAddress)).thenReturn(failedConnectFuture);
        when(failedConnectFuture.sync()).thenThrow(new RuntimeException("connect timeout"));
        when(clientBootstrap.connect(secondAddress)).thenReturn(successfulConnectFuture);
        when(successfulConnectFuture.sync()).thenReturn(successfulConnectFuture);
        when(successfulConnectFuture.channel()).thenReturn(channel);
        when(successfulConnectFuture.addListeners()).thenReturn(successfulConnectFuture);
        when(handshakeAgent.isDone()).thenReturn(true);

        NodeClientConfig config = NodeClientConfig.builder()
                .initialRetryDelayMs(0)
                .enableConnectionLogging(false)
                .build();

        Session session = new Session(socketAddressSupplier, clientBootstrap, config, handshakeAgent, new Agent[0]);

        session.start();

        verify(socketAddressSupplier, times(2)).get();
        verify(clientBootstrap).connect(firstAddress);
        verify(clientBootstrap).connect(secondAddress);
    }
}
