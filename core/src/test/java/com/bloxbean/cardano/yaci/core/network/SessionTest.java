package com.bloxbean.cardano.yaci.core.network;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionTest {

    @Test
    void startGetsFreshSocketAddressForEachRetryAttempt() throws InterruptedException {
        Bootstrap bootstrap = mock(Bootstrap.class);
        when(bootstrap.connect(any(SocketAddress.class))).thenThrow(new RuntimeException("connect failed"));

        AtomicInteger supplierCalls = new AtomicInteger();
        Supplier<SocketAddress> socketAddressSupplier = () -> {
            supplierCalls.incrementAndGet();
            return new InetSocketAddress("127.0.0.1", 3001);
        };

        NodeClientConfig config = NodeClientConfig.builder()
                .initialRetryDelayMs(0)
                .maxRetryAttempts(2)
                .enableConnectionLogging(false)
                .build();

        Session session = new Session(socketAddressSupplier, bootstrap, config, null, new Agent[0]);

        assertThrows(RuntimeException.class, session::start);

        assertEquals(3, supplierCalls.get());
        verify(bootstrap, times(3)).connect(any(SocketAddress.class));
    }

    @Test
    void startDoesNotRetryWhenAutoReconnectIsDisabled() {
        Bootstrap bootstrap = mock(Bootstrap.class);
        when(bootstrap.connect(any(SocketAddress.class))).thenThrow(new RuntimeException("connect failed"));

        AtomicInteger supplierCalls = new AtomicInteger();
        Supplier<SocketAddress> socketAddressSupplier = () -> {
            supplierCalls.incrementAndGet();
            return new InetSocketAddress("127.0.0.1", 3001);
        };

        NodeClientConfig config = NodeClientConfig.builder()
                .autoReconnect(false)
                .initialRetryDelayMs(0)
                .maxRetryAttempts(10)
                .enableConnectionLogging(false)
                .build();

        Session session = new Session(socketAddressSupplier, bootstrap, config, null, new Agent[0]);

        assertThrows(RuntimeException.class, session::start);

        assertEquals(1, supplierCalls.get());
        verify(bootstrap, times(1)).connect(any(SocketAddress.class));
    }

    @Test
    void nodeClientStartPropagatesStartupFailureWhenConfigured() {
        NodeClientConfig config = NodeClientConfig.builder()
                .autoReconnect(false)
                .enableConnectionLogging(false)
                .propagateStartupFailure(true)
                .build();

        FailingNodeClient client = new FailingNodeClient(config);

        try {
            assertThrows(NodeClientException.class, client::start);
            assertFalse(client.isRunning());
            assertThrows(NodeClientException.class, client::start);
            assertFalse(client.isRunning());
        } finally {
            client.shutdown();
        }
    }

    private static class FailingNodeClient extends NodeClient {
        private FailingNodeClient(NodeClientConfig config) {
            super(config, new HandshakeAgent(N2NVersionTableConstant.v4AndAbove(0)));
        }

        @Override
        protected SocketAddress createSocketAddress() {
            return new InetSocketAddress("127.0.0.1", 1);
        }

        @Override
        protected EventLoopGroup configureEventLoopGroup() {
            return new NioEventLoopGroup(1);
        }

        @Override
        protected Class getChannelClass() {
            return NioSocketChannel.class;
        }

        @Override
        protected void configureChannel(Bootstrap bootstrap) {
            throw new IllegalStateException("configure failed");
        }
    }
}
