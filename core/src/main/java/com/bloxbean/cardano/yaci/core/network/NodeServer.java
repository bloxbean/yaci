package com.bloxbean.cardano.yaci.core.network;

import com.bloxbean.cardano.yaci.core.network.handlers.MiniProtoClientInboundHandler;
import com.bloxbean.cardano.yaci.core.network.handlers.MiniProtoRequestDataEncoder;
import com.bloxbean.cardano.yaci.core.network.handlers.MiniProtoStreamingByteToMessageDecoder;
import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.Reason;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

@Slf4j
public class NodeServer {

    private final String host = "0.0.0.0";
    private final int port;
    private SessionListener sessionListener;
    private HandshakeAgent handshakeAgent;
    private Agent[] agents;
    private EventLoopGroup workerGroup;
    private ServerSession session;

    public NodeServer(int port, HandshakeAgent handshakeAgent, Agent... agents) {
        this.port = port;
        this.sessionListener = new SessionListenerAdapter();

        this.handshakeAgent = handshakeAgent;
        this.agents = agents;
        this.workerGroup = configureEventLoopGroup();

        attachHandshakeListener();
    }

    private void attachHandshakeListener() {
        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                for (Agent agent : agents) {
                    agent.setProtocolVersion(handshakeAgent.getProtocolVersion());
                }
            }

            @Override
            public void handshakeError(Reason reason) {
                for (Agent agent : agents) {
                    agent.setProtocolVersion(handshakeAgent.getProtocolVersion());
                }
            }
        });
    }

    public void start() {
        if (session != null)
            throw new RuntimeException("Session already available. Only one session is allowed per N2NClient. To start again, please call shutdown() first.");

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel ch) throws Exception {
                            ch.pipeline().addLast(new MiniProtoRequestDataEncoder(),
                                    new MiniProtoStreamingByteToMessageDecoder(agents),
                                    new MiniProtoClientInboundHandler(handshakeAgent, agents));
                        }
                    });

            session = new ServerSession(port, b, handshakeAgent, agents);
            session.setSessionListener(sessionListener);
            session.start();

        } catch (Exception e) {
            log.error("Error", e);
        }
    }

    protected SocketAddress createSocketAddress() {
        return new InetSocketAddress(host, port);
    }

    public boolean isRunning() {
        return session != null;
    }

    public void shutdown() {
        log.info("Shutdown connection !!!");

        if (session != null) {
            session.disableReconnection();
            session.dispose();
            session = null;
        }

        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }

    public void restartSession() {
        if (session != null) {
            session.disableReconnection();
            session.dispose();
            session = null;
        }

        //TODO -- find a better way to wait for session to close
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        for (var agent : agents) {
            agent.reset();
        }

        start();
    }


    /**
     * Create and configure an EventLoopGroup. This method is invoked from {@link #start()}
     *
     * @return
     */
    protected EventLoopGroup configureEventLoopGroup() {
        return new NioEventLoopGroup();
    }


    class SessionListenerAdapter implements SessionListener {
        public SessionListenerAdapter() {

        }

        @Override
        public void disconnected() {
            log.info("Connection closed !!!");
            if (session != null) {
                session.dispose();
            }

            for (Agent agent : agents) {
                agent.disconnected();
            }

            //TODO some delay
            //Try to start again
            if (session != null && session.shouldReconnect()) {
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
