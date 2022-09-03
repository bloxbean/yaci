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
public class N2NClient extends NodeClient {
    private String host;
    private int port;

    public N2NClient(String host, int port, HandshakeAgent handshakeAgent, Agent... agents) {
        super(handshakeAgent, agents);
        this.host = host;
        this.port = port;
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
