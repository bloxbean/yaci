package com.bloxbean.cardano.yaci.core.network;

import com.bloxbean.cardano.yaci.core.network.handlers.MiniProtoClientInboundHandler;
import com.bloxbean.cardano.yaci.core.network.handlers.MiniProtoRequestDataEncoder;
import com.bloxbean.cardano.yaci.core.network.handlers.MiniProtoStreamingByteToMessageDecoder;
import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import lombok.extern.slf4j.Slf4j;

import java.net.SocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

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
    }

    public void start() {
        if (session != null)
            throw new RuntimeException("Session already available. Only one session is allowed per N2NClient. To start again, please call shutdown() first.");

        try {
            Bootstrap b = new Bootstrap();
            b.group(workerGroup);
            b.channel(getChannelClass());

            configureChannel(b);

            List<Agent> allAgents = Arrays.stream(agents).collect(Collectors.toList());
            allAgents.add(0, handshakeAgent);

            b.handler(new ChannelInitializer<Channel>() {

                @Override
                public void initChannel(Channel ch)
                        throws Exception {
                    //ch.pipeline().addLast("readTimeoutHandler", new ReadTimeoutHandler(30));
                    ch.pipeline().addLast(new MiniProtoRequestDataEncoder(),
                            new MiniProtoStreamingByteToMessageDecoder(agents),
                            new MiniProtoClientInboundHandler(handshakeAgent, agents));
                }
            });

            SocketAddress socketAddress = null;
            socketAddress = createSocketAddress();

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
            log.info("Connection closed !!!");
            if (session != null) {
                session.dispose();
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
