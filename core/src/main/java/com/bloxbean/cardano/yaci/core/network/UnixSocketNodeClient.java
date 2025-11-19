package com.bloxbean.cardano.yaci.core.network;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.util.OSUtil;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollDomainSocketChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.kqueue.KQueueDomainSocketChannel;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.unix.DomainSocketAddress;

import java.io.File;
import java.net.SocketAddress;

/**
 * This is the main class to initialize single or multiple agents for Node-to-client mini-protocol and setup channel handlers to send / process
 * network bytes.
 */
public class UnixSocketNodeClient extends NodeClient {
    private String nodeSocketFile;

    /**
     * Constructor with NodeClientConfig for configurable connection behavior.
     *
     * @param nodeSocketFile the path to the Unix domain socket
     * @param config the connection configuration
     * @param handshakeAgent the handshake agent
     * @param agents the protocol agents
     */
    public UnixSocketNodeClient(String nodeSocketFile, NodeClientConfig config, HandshakeAgent handshakeAgent, Agent... agents) {
        super(config, handshakeAgent, agents);
        this.nodeSocketFile = nodeSocketFile;
    }

    /**
     * Constructor with default configuration (for backward compatibility).
     *
     * @param nodeSocketFile the path to the Unix domain socket
     * @param handshakeAgent the handshake agent
     * @param agents the protocol agents
     */
    public UnixSocketNodeClient(String nodeSocketFile, HandshakeAgent handshakeAgent, Agent... agents) {
        this(nodeSocketFile, NodeClientConfig.defaultConfig(), handshakeAgent, agents);
    }

    @Override
    protected SocketAddress createSocketAddress() {
        return new DomainSocketAddress(new File(nodeSocketFile));
    }

    @Override
    protected EventLoopGroup configureEventLoopGroup() {
        if (OSUtil.getOperatingSystem() == OSUtil.OS.MAC)
            return new KQueueEventLoopGroup();
        else
            return new EpollEventLoopGroup();
    }

    @Override
    protected Class getChannelClass() {
        if (OSUtil.getOperatingSystem() == OSUtil.OS.MAC) {
            return KQueueDomainSocketChannel.class;
        } else
            return EpollDomainSocketChannel.class;
    }

    @Override
    protected void configureChannel(Bootstrap bootstrap) {

    }
}
