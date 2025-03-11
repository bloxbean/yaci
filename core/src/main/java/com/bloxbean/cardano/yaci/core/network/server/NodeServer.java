package com.bloxbean.cardano.yaci.core.network.server;

import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class NodeServer {
    private final int port;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private Channel serverChannel;
    private VersionTable versionTable;
    private final static Map<Channel, NodeServerSession> sessions = new ConcurrentHashMap<>();

    public NodeServer(int port, VersionTable versionTable) {
        this.port = port;
        this.bossGroup = new NioEventLoopGroup(1);
        this.workerGroup = new NioEventLoopGroup();
        this.versionTable = versionTable;
    }

    public void start() {
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            NodeServerSession session = new NodeServerSession(ch, versionTable);
                            sessions.put(ch, session);
                        }
                    })
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true);

            ChannelFuture future = bootstrap.bind(port).sync();
            serverChannel = future.channel();
            log.info("NodeServer started on port {}", port);

            serverChannel.closeFuture().sync();
        } catch (InterruptedException e) {
            log.error("NodeServer interrupted", e);
        } finally {
            shutdown();
        }
    }

    public void shutdown() {
        log.info("Shutting down NodeServer...");
        for (NodeServerSession session : sessions.values()) {
            session.close();
        }
        if (serverChannel != null) {
            serverChannel.close();
        }
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }

    public static void removeSession(Channel channel) {
        NodeServerSession session = sessions.remove(channel);
        if (session != null) {
            session.close();
        }
    }

}
