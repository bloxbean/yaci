package com.bloxbean.cardano.yaci.core.network;

import com.bloxbean.cardano.yaci.core.network.handlers.MiniProtoClientInboundHandler;
import com.bloxbean.cardano.yaci.core.network.handlers.MiniProtoRequestDataEncoder;
import com.bloxbean.cardano.yaci.core.network.handlers.MiniProtoStreamingByteToMessageDecoder;
import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * This is the main class to initialize single or multiple agents and setup channel handlers to send / process
 * network bytes.
 */
@Slf4j
public class N2NClient {
    private String host;
    private int port;
    private SessionListener sessionListener;
    private HandshakeAgent handshakeAgent;
    private Agent[] agents;
    private EventLoopGroup workerGroup;
    private Session session;

    public N2NClient(String host, int port, HandshakeAgent handshakeAgent, Agent... agents) {
        this.host = host;
        this.port = port;
        this.sessionListener = new SessionListenerAdapter();

        this.handshakeAgent = handshakeAgent;
        this.agents = agents;
        this.workerGroup = new NioEventLoopGroup();
    }

    public void start() {
        if (session != null)
            throw new RuntimeException("Session already available. Only one session is allowed per N2NClient. To start again, please call shutdown() first.");

        try {
            Bootstrap b = new Bootstrap();
            //EventLoopGroup workerGroup = new NioEventLoopGroup();
            b.group(workerGroup);
            b.channel(NioSocketChannel.class);
            b.option(ChannelOption.SO_KEEPALIVE, true);

            List<Agent> allAgents = Arrays.stream(agents).collect(Collectors.toList());
            allAgents.add(0, handshakeAgent);

            b.handler(new ChannelInitializer<SocketChannel>() {

                @Override
                public void initChannel(SocketChannel ch)
                        throws Exception {
                  //ch.pipeline().addLast("readTimeoutHandler", new ReadTimeoutHandler(30));
                    ch.pipeline().addLast(new MiniProtoRequestDataEncoder(),
                            new MiniProtoStreamingByteToMessageDecoder(agents),
                            new MiniProtoClientInboundHandler(handshakeAgent, agents));
                }
            });

            SocketAddress socketAddress = null;
            socketAddress = new InetSocketAddress(host, port);

            session = new Session(socketAddress, b, handshakeAgent, agents);
            session.setSessionListener(sessionListener);
            session.start();
        } catch (Exception e) {
            log.error("Error", e);
        }
    }

    public void shutdown() {
        log.info("Shutdown ----");

        if (session != null) {
            session.disableReconnection();
            session.dispose();
            session = null;
        }

        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }

    class SessionListenerAdapter implements SessionListener {
        public SessionListenerAdapter() {

        }

        @Override
        public void disconnected() {
            log.error("Connection closed or error");
            if (session != null) {
                session.dispose();
            }

            //TODO some delay
            //Try to start again
            if (session.shouldReconnect()) {
                log.warn("Trying to reconnect !!!");
                session = null; //reset session before creating a new one.
                start();
            }
        }

        @Override
        public void connected() {
            log.info("Connected !!!");
        }
    }
}
