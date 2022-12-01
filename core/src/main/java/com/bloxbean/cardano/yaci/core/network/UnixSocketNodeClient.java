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

    public UnixSocketNodeClient(String nodeSocketFile, HandshakeAgent handshakeAgent, Agent... agents) {
        super(handshakeAgent, agents);
        this.nodeSocketFile = nodeSocketFile;
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
