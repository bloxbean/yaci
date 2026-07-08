package com.bloxbean.cardano.yaci.core.network;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

        AtomicInteger resolveAttempts = new AtomicInteger();
        Supplier<SocketAddress> socketAddressSupplier = () ->
                resolveAttempts.getAndIncrement() == 0 ? firstAddress : secondAddress;

        RecordingBootstrap clientBootstrap = new RecordingBootstrap();
        HandshakeAgent handshakeAgent = new DoneHandshakeAgent();

        NodeClientConfig config = NodeClientConfig.builder()
                .initialRetryDelayMs(0)
                .enableConnectionLogging(false)
                .build();

        Session session = new Session(socketAddressSupplier, clientBootstrap, config, handshakeAgent, new Agent[0]);

        session.start();

        assertEquals(2, resolveAttempts.get());
        assertEquals(List.of(firstAddress, secondAddress), clientBootstrap.connectAttempts);
    }

    private static class RecordingBootstrap extends Bootstrap {
        private final EmbeddedChannel channel = new EmbeddedChannel();
        private final List<SocketAddress> connectAttempts = new ArrayList<>();

        @Override
        public ChannelFuture connect(SocketAddress remoteAddress) {
            connectAttempts.add(remoteAddress);
            DefaultChannelPromise promise = new DefaultChannelPromise(channel);
            if (connectAttempts.size() == 1) {
                return promise.setFailure(new RuntimeException("connect timeout"));
            }

            return promise.setSuccess();
        }
    }

    private static class DoneHandshakeAgent extends HandshakeAgent {
        private DoneHandshakeAgent() {
            super(null);
        }

        @Override
        public boolean isDone() {
            return true;
        }
    }
}
