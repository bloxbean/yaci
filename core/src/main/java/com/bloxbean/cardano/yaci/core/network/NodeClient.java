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
    protected NodeClientConfig config;

    public NodeClient() {

    }

    /**
     * Constructor with NodeClientConfig for configurable connection behavior.
     *
     * @param config the connection configuration
     * @param handshakeAgent the handshake agent
     * @param agents the protocol agents
     */
    public NodeClient(NodeClientConfig config, HandshakeAgent handshakeAgent, Agent... agents) {
        this.sessionListener = new SessionListenerAdapter();
        this.config = config != null ? config : NodeClientConfig.defaultConfig();

        this.handshakeAgent = handshakeAgent;
        this.agents = agents;
        this.workerGroup = configureEventLoopGroup();

        attachHandshakeListener();
    }

    /**
     * Constructor with default configuration (for backward compatibility).
     *
     * @param handshakeAgent the handshake agent
     * @param agents the protocol agents
     */
    public NodeClient(HandshakeAgent handshakeAgent, Agent... agents) {
        this(NodeClientConfig.defaultConfig(), handshakeAgent, agents);
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
                    //ch.pipeline().addLast("readTimeoutHandler", new ReadTimeoutHandler(30));
                    ch.pipeline().addLast(new MiniProtoRequestDataEncoder(),
                            new MiniProtoStreamingByteToMessageDecoder(agents),
                            new MiniProtoClientInboundHandler(handshakeAgent, agents));
                }
            });

            SocketAddress socketAddress = createSocketAddress();

            session = new Session(socketAddress, b, config, handshakeAgent, agents);
            session.setSessionListener(sessionListener);
            session.start();
        } catch (Exception e) {
            log.error("Error", e);
        }
    }

    public boolean isRunning() {
        return session != null;
    }

    /**
     * Get the current NodeClientConfig.
     * This is primarily for use by subclasses to access configuration options.
     *
     * @return the current configuration
     */
    protected NodeClientConfig getConfig() {
        return config;
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

        //TODO -- find a better way to wait for session to close
        try {
            Thread.sleep(1000);
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
            if (showConnectionLog())
                log.info("Connected !!!");
        }
    }

    private boolean showConnectionLog() {
        return (config != null && config.isEnableConnectionLogging()) &&
                (log.isDebugEnabled() || (handshakeAgent != null && !handshakeAgent.isSuppressConnectionInfoLog()));
    }
}
