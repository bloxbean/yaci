package com.bloxbean.cardano.yaci.core.network;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * This is the main class to initialize single or multiple agents for Node-to-node mini-protocol and setup channel handlers to send / process
 * network bytes.
 */
@Slf4j
public class TCPNodeClient extends NodeClient {
    private String host;
    private int port;

    /**
     * Constructor with NodeClientConfig for configurable connection behavior.
     *
     * @param host the host to connect to
     * @param port the port to connect to
     * @param config the connection configuration
     * @param handshakeAgent the handshake agent
     * @param agents the protocol agents
     */
    public TCPNodeClient(String host, int port, NodeClientConfig config, HandshakeAgent handshakeAgent, Agent... agents) {
        super(config, handshakeAgent, agents);
        this.host = host;
        this.port = port;
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
        return new InetSocketAddress(host, port);
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
    }
}
