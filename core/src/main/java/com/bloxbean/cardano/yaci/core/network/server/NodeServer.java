package com.bloxbean.cardano.yaci.core.network.server;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
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
    private ChainState chainState;
    private final static Map<Channel, NodeServerSession> sessions = new ConcurrentHashMap<>();

    public NodeServer(int port, VersionTable versionTable, ChainState chainState) {
        this.port = port;
        this.bossGroup = new NioEventLoopGroup(1);
        this.workerGroup = new NioEventLoopGroup();
        this.versionTable = versionTable;
        this.chainState = chainState;
    }

    public void start() {
        try {
            log.info("Initializing NodeServer on port {}", port);

            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            log.info("New connection from: {}", ch.remoteAddress());
                            log.info("Local address: {}", ch.localAddress());

                            try {
                                NodeServerSession session = new NodeServerSession(ch, versionTable, chainState);
                                sessions.put(ch, session);
                                log.info("Created session for client: {}", ch.remoteAddress());
                            } catch (Exception e) {
                                log.error("Error creating session for client {}", ch.remoteAddress(), e);
                                ch.close();
                            }
                        }
                    })
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true);

            log.info("Binding to port {}", port);
            ChannelFuture future = bootstrap.bind(port);
            future.addListener(bindFuture -> {
                if (bindFuture.isSuccess()) {
                    log.info("NodeServer successfully bound to port {}", port);
                } else {
                    log.error("Failed to bind to port {}", port, bindFuture.cause());
                }
            });

            future.sync();
            serverChannel = future.channel();
            log.info("NodeServer is now listening on port {} and waiting for connections", port);
            log.info("Server socket address: {}", serverChannel.localAddress());

            serverChannel.closeFuture().sync();
        } catch (InterruptedException e) {
            log.error("NodeServer interrupted", e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("NodeServer failed to start", e);
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

    /**
     * Notify all agents in all sessions that new blockchain data is available.
     * Each agent can decide based on its current state whether to react to this notification.
     */
    public void notifyNewDataAvailable() {
        log.debug("Notifying {} sessions about new data availability", sessions.size());

        for (NodeServerSession session : sessions.values()) {
            try {
                // Notify all agents in this session
                for (Agent agent : session.getAgents()) {
                    try {
                        agent.onNewDataAvailable();
                    } catch (Exception e) {
                        log.warn("Error notifying agent {} about new data: {}",
                                agent.getClass().getSimpleName(), e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("Error notifying session about new data", e);
            }
        }
    }

}
