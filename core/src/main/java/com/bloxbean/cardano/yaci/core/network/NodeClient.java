package com.bloxbean.cardano.yaci.core.network;

import com.bloxbean.cardano.yaci.core.network.handlers.MiniProtoClientInboundHandler;
import com.bloxbean.cardano.yaci.core.network.handlers.MiniProtoRequestDataEncoder;
import com.bloxbean.cardano.yaci.core.network.handlers.MiniProtoStreamingByteToMessageDecoder;
import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.Reason;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import lombok.extern.slf4j.Slf4j;

import java.net.SocketAddress;

@Slf4j
public abstract class NodeClient {
    private SessionListener sessionListener;
    private HandshakeAgent handshakeAgent;
    private Agent[] agents;
    private EventLoopGroup workerGroup;
    private Session session;

    public NodeClient() {

    }

    public NodeClient(HandshakeAgent handshakeAgent, Agent... agents) {
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
                for (Agent agent: agents) {
                    agent.setProtocolVersion(handshakeAgent.getProtocolVersion());
                }
            }

            @Override
            public void handshakeError(Reason reason) {
                for (Agent agent: agents) {
                    agent.setProtocolVersion(handshakeAgent.getProtocolVersion());
                }
            }
        });
    }

    public void start() {
        if (session != null)
            throw new RuntimeException("Session already available. Only one session is allowed per N2NClient. To start again, please call shutdown() first.");

        try {
            Bootstrap b = new Bootstrap();
            b.group(workerGroup);
            b.channel(getChannelClass());

            configureChannel(b);

            b.handler(new ChannelInitializer<>() {

                @Override
                public void initChannel(Channel ch)
                        throws Exception {
                    ch.pipeline().addLast(new MiniProtoRequestDataEncoder(),
                            new MiniProtoStreamingByteToMessageDecoder(agents),
                            new MiniProtoClientInboundHandler(handshakeAgent, agents));
                }
            });

            SocketAddress socketAddress = createSocketAddress();

            session = new Session(socketAddress, b, handshakeAgent, agents);
            session.setSessionListener(sessionListener);
            session.start();
        } catch (Exception e) {
            log.error("Error", e);
        }
    }

    public boolean isRunning() {
        return session != null;
    }

    public void shutdown() {
        if (showConnectionLog())
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

        // Session.dispose() now properly waits for channel closure
        // Small delay to ensure any remaining event loop processing is complete
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        for (var agent: agents) {
            agent.reset();
        }

        start();
    }

    /**
     * Create Socket address. This method is invoked from  {@link #start()}
     *
     * @return
     */
    protected abstract SocketAddress createSocketAddress();

    /**
     * Create and configure an EventLoopGroup. This method is invoked from {@link #start()}
     *
     * @return
     */
    protected abstract EventLoopGroup configureEventLoopGroup();

    /**
     * Get channel class
     *
     * @return
     */
    protected abstract Class getChannelClass();

    /**
     * Configure channel
     *
     * @param bootstrap
     */
    protected abstract void configureChannel(Bootstrap bootstrap);

    class SessionListenerAdapter implements SessionListener {
        public SessionListenerAdapter() {

        }

        @Override
        public void disconnected() {
            if (showConnectionLog())
                log.info("Connection closed !!!");
            
            if (session != null) {
                session.dispose();
            }

            for (Agent agent: agents) {
                agent.disconnected();
            }

            // Try to start again
            if (session != null && session.shouldReconnect()) {
                log.warn("Trying to reconnect !!!");
                session = null; //reset session before creating a new one.
                start();
            }
        }

        @Override
        public void connected() {
            if (showConnectionLog())
                log.info("Connected !!!");
        }
    }

    private boolean showConnectionLog() {
        return log.isDebugEnabled() || (handshakeAgent != null && !handshakeAgent.isSuppressConnectionInfoLog());
    }
}
