package com.bloxbean.cardano.yaci.core.network;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ThreadLocalRandom;

/**
 * This is the main class to initialize single or multiple agents for Node-to-node mini-protocol and setup channel handlers to send / process
 * network bytes.
 */
@Slf4j
public class TCPNodeClient extends NodeClient {
    private String host;
    private int port;
    private DnsRotatingSocketAddressSupplier socketAddressSupplier;

    /**
     * Constructor with NodeClientConfig for configurable connection behavior.
     *
     * @param host the host to connect to
     * @param port the port to connect to
     * @param config the connection configuration
     * @param handshakeAgent the handshake agent
     * @param agents the protocol agents
     */
    public TCPNodeClient(String host, int port, NodeClientConfig config, HandshakeAgent handshakeAgent,
                         Agent... agents) {
        super(config, handshakeAgent, agents);
        this.host = host;
        this.port = port;
        this.socketAddressSupplier = new DnsRotatingSocketAddressSupplier(host, port);
    }

    /**
     * Constructor with default configuration (for backward compatibility).
     *
     * @param host the host to connect to
     * @param port the port to connect to
     * @param handshakeAgent the handshake agent
     * @param agents the protocol agents
     */
    public TCPNodeClient(String host, int port, HandshakeAgent handshakeAgent, Agent... agents) {
        this(host, port, NodeClientConfig.defaultConfig(), handshakeAgent, agents);
    }

    @Override
    protected SocketAddress createSocketAddress() {
        return socketAddressSupplier.get();
    }

    @Override
    protected EventLoopGroup configureEventLoopGroup() {
        return new NioEventLoopGroup();
    }

    @Override
    protected Class getChannelClass() {
        return NioSocketChannel.class;
    }

    @Override
    protected void configureChannel(Bootstrap bootstrap) {
        bootstrap.option(ChannelOption.SO_KEEPALIVE, true);
        bootstrap.option(ChannelOption.TCP_NODELAY, true);
        bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, getConfig().getConnectionTimeoutMs());
    }

    private static class DnsRotatingSocketAddressSupplier {
        private final String host;
        private final int port;
        private final AtomicInteger cursor = new AtomicInteger(ThreadLocalRandom.current().nextInt());

        private DnsRotatingSocketAddressSupplier(String host, int port) {
            this.host = host;
            this.port = port;
        }

        public SocketAddress get() {
            try {
                InetAddress[] addresses = InetAddress.getAllByName(host);
                int index = Math.floorMod(cursor.getAndIncrement(), addresses.length);
                return new InetSocketAddress(addresses[index], port);
            } catch (UnknownHostException e) {
                throw new IllegalStateException("Unable to resolve " + host + ":" + port, e);
            }
        }
    }
}
