package com.bloxbean.cardano.yaci.core.network;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void startTriesNextCandidateWhenFirstCandidateFails() throws InterruptedException {
        Bootstrap bootstrap = mock(Bootstrap.class);
        ChannelFuture channelFuture = mockSuccessfulChannelFuture();
        HandshakeAgent handshakeAgent = mock(HandshakeAgent.class);
        when(handshakeAgent.isDone()).thenReturn(true);

        SocketAddress first = new InetSocketAddress("127.0.0.1", 3001);
        SocketAddress second = new InetSocketAddress("127.0.0.2", 3001);
        when(bootstrap.connect(first)).thenThrow(new RuntimeException("first candidate failed"));
        when(bootstrap.connect(second)).thenReturn(channelFuture);

        NodeClientConfig config = NodeClientConfig.builder()
                .autoReconnect(false)
                .enableConnectionLogging(false)
                .build();

        Session session = new Session(() -> List.of(first, second), bootstrap, config, handshakeAgent, new Agent[0]);

        assertSame(session, session.start());
        verify(bootstrap, times(1)).connect(first);
        verify(bootstrap, times(1)).connect(second);
    }

    @Test
    void startTriesAllCandidatesOnceBeforeFailingWhenRetriesAreDisabled() {
        Bootstrap bootstrap = mock(Bootstrap.class);
        AtomicInteger providerCalls = new AtomicInteger();

        SocketAddress first = new InetSocketAddress("127.0.0.1", 3001);
        SocketAddress second = new InetSocketAddress("127.0.0.2", 3001);
        when(bootstrap.connect(any(SocketAddress.class))).thenThrow(new RuntimeException("connect failed"));

        NodeClientConfig config = NodeClientConfig.builder()
                .autoReconnect(false)
                .maxRetryAttempts(0)
                .enableConnectionLogging(false)
                .build();

        Session session = new Session(() -> {
            providerCalls.incrementAndGet();
            return List.of(first, second);
        }, bootstrap, config, null, new Agent[0]);

        assertThrows(RuntimeException.class, session::start);
        assertEquals(1, providerCalls.get());
        verify(bootstrap, times(1)).connect(first);
        verify(bootstrap, times(1)).connect(second);
    }

    @Test
    void startRetriesWhenAddressResolutionFailsTransiently() throws InterruptedException {
        Bootstrap bootstrap = mock(Bootstrap.class);
        ChannelFuture channelFuture = mockSuccessfulChannelFuture();
        HandshakeAgent handshakeAgent = mock(HandshakeAgent.class);
        when(handshakeAgent.isDone()).thenReturn(true);

        SocketAddress socketAddress = new InetSocketAddress("127.0.0.1", 3001);
        when(bootstrap.connect(socketAddress)).thenReturn(channelFuture);

        AtomicInteger providerCalls = new AtomicInteger();
        SocketAddressProvider socketAddressProvider = () -> {
            if (providerCalls.incrementAndGet() == 1) {
                throw new IllegalStateException("temporary dns failure");
            }
            return List.of(socketAddress);
        };

        NodeClientConfig config = NodeClientConfig.builder()
                .autoReconnect(true)
                .initialRetryDelayMs(0)
                .maxRetryAttempts(1)
                .enableConnectionLogging(false)
                .build();

        Session session = new Session(socketAddressProvider, bootstrap, config, handshakeAgent, new Agent[0]);

        assertSame(session, session.start());
        assertEquals(2, providerCalls.get());
        verify(bootstrap, times(1)).connect(socketAddress);
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

    @Test
    void nodeClientStartPropagatesStartupFailureAfterAllCandidatesFail() {
        Bootstrap bootstrap = mock(Bootstrap.class);
        SocketAddress first = new InetSocketAddress("127.0.0.1", 3001);
        SocketAddress second = new InetSocketAddress("127.0.0.2", 3001);
        when(bootstrap.connect(first)).thenThrow(new RuntimeException("first candidate failed"));
        when(bootstrap.connect(second)).thenThrow(new RuntimeException("second candidate failed"));

        NodeClientConfig config = NodeClientConfig.builder()
                .autoReconnect(false)
                .maxRetryAttempts(0)
                .enableConnectionLogging(false)
                .propagateStartupFailure(true)
                .build();

        CandidateFailingNodeClient client = new CandidateFailingNodeClient(config, bootstrap, List.of(first, second));

        try {
            assertThrows(NodeClientException.class, client::start);
            verify(bootstrap, times(1)).connect(first);
            verify(bootstrap, times(1)).connect(second);
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

    private static class CandidateFailingNodeClient extends NodeClient {
        private final Bootstrap bootstrap;
        private final List<SocketAddress> candidates;

        private CandidateFailingNodeClient(NodeClientConfig config, Bootstrap bootstrap,
                                           List<SocketAddress> candidates) {
            super(config, mock(HandshakeAgent.class));
            this.bootstrap = bootstrap;
            this.candidates = candidates;
        }

        @Override
        protected SocketAddress createSocketAddress() {
            return candidates.get(0);
        }

        @Override
        protected List<SocketAddress> createSocketAddressCandidates() {
            return candidates;
        }

        @Override
        protected Bootstrap createBootstrap() {
            return bootstrap;
        }

        @Override
        protected EventLoopGroup configureEventLoopGroup() {
            return mock(EventLoopGroup.class);
        }

        @Override
        protected Class getChannelClass() {
            return NioSocketChannel.class;
        }

        @Override
        protected void configureChannel(Bootstrap bootstrap) {
        }
    }

    private ChannelFuture mockSuccessfulChannelFuture() throws InterruptedException {
        ChannelFuture channelFuture = mock(ChannelFuture.class);
        Channel channel = mock(Channel.class);
        when(channelFuture.sync()).thenReturn(channelFuture);
        when(channelFuture.channel()).thenReturn(channel);
        return channelFuture;
    }
}
