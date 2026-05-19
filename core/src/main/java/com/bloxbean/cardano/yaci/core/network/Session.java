package com.bloxbean.cardano.yaci.core.network;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import lombok.extern.slf4j.Slf4j;

import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * This class tries to open the network connection. The session gets destroyed during disconnection event.
 */
@Slf4j
class Session implements Disposable {
    private final Supplier<SocketAddress> socketAddressSupplier;
    private final Bootstrap clientBootstrap;
    private Channel activeChannel;
    private final AtomicBoolean shouldReconnect;
    private final HandshakeAgent handshakeAgent;
    private final Agent[] agents;
    private final NodeClientConfig config;

    private SessionListener sessionListener;

    /**
     * Constructor with NodeClientConfig for configurable connection behavior.
     *
     * @param socketAddressSupplier the socket address supplier to use for each connection attempt
     * @param clientBootstrap the Netty bootstrap
     * @param config the connection configuration
     * @param handshakeAgent the handshake agent
     * @param agents the protocol agents
     */
    public Session(Supplier<SocketAddress> socketAddressSupplier, Bootstrap clientBootstrap, NodeClientConfig config,
                   HandshakeAgent handshakeAgent, Agent[] agents) {
        this.socketAddressSupplier = socketAddressSupplier;
        this.clientBootstrap = clientBootstrap;
        this.config = config != null ? config : NodeClientConfig.defaultConfig();
        this.shouldReconnect = new AtomicBoolean(this.config.isAutoReconnect());

        this.handshakeAgent = handshakeAgent;
        this.agents = agents;
    }

    /**
     * Constructor with NodeClientConfig for configurable connection behavior.
     *
     * @param socketAddress the socket address to connect to
     * @param clientBootstrap the Netty bootstrap
     * @param config the connection configuration
     * @param handshakeAgent the handshake agent
     * @param agents the protocol agents
     */
    public Session(SocketAddress socketAddress, Bootstrap clientBootstrap, NodeClientConfig config,
                   HandshakeAgent handshakeAgent, Agent[] agents) {
        this(() -> socketAddress, clientBootstrap, config, handshakeAgent, agents);
    }

    /**
     * Constructor with default configuration (for backward compatibility).
     *
     * @param socketAddress the socket address to connect to
     * @param clientBootstrap the Netty bootstrap
     * @param handshakeAgent the handshake agent
     * @param agents the protocol agents
     * @deprecated Use {@link #Session(SocketAddress, Bootstrap, NodeClientConfig, HandshakeAgent, Agent[])} instead
     */
    @Deprecated
    public Session(SocketAddress socketAddress, Bootstrap clientBootstrap, HandshakeAgent handshakeAgent,
                   Agent[] agents) {
        this(socketAddress, clientBootstrap, NodeClientConfig.defaultConfig(), handshakeAgent, agents);
    }

    public void setSessionListener(SessionListener sessionListener) {
        if (this.sessionListener != null)
            throw new RuntimeException("SessionListener is already there. Can't set another SessionListener");

        this.sessionListener = sessionListener;
    }

    public Disposable start() throws InterruptedException {
        //Create a new connectFuture
        ChannelFuture connectFuture = null;
        long retriesUsed = 0;
        // Always try to connect at least once, then retry only if shouldReconnect is true
        while (connectFuture == null) {
            try {
                SocketAddress socketAddress = socketAddressSupplier.get();
                if (showConnectionLog())
                    log.info("Connecting to {} (attempt {}, max retries: {})",
                            socketAddress, retriesUsed + 1, config.getMaxRetryAttempts());
                connectFuture = clientBootstrap.connect(socketAddress).sync();
            } catch (Exception e) {
                log.error("Connection failed", e);
                if (!shouldRetry(retriesUsed)) {
                    throw e;
                }
                retriesUsed++;
                Thread.sleep(config.getInitialRetryDelayMs());
                log.debug("Trying to reconnect !!!");
            }
        }

        handshakeAgent.reset();
        for (Agent agent: agents) {
            agent.disconnected();
            agent.reset();
        }

        activeChannel = connectFuture.channel();

        handshakeAgent.setChannel(activeChannel);
        for (Agent agent: agents) {
            agent.setChannel(activeChannel);
        }

        connectFuture.addListeners((ChannelFuture cf) -> {
            if (cf.isSuccess()) {
                if (showConnectionLog())
                    log.info("Connection established");
                if (sessionListener != null)
                    sessionListener.connected();
                //Listen to the channel closing
                var closeFuture = activeChannel.closeFuture();
                closeFuture.addListeners((ChannelFuture closeFut) -> {
                    if (log.isDebugEnabled())
                        log.warn("Channel closed !!!");
                    if (sessionListener != null)
                        sessionListener.disconnected();
                });

            } else {
                log.error("Connection failed");

                if (sessionListener != null)
                    sessionListener.disconnected();
            }
        });

        handshake();
        return this;
    }

    /**
     * Call this to end the session
     */
    @Override
    public void dispose() {
        if (showConnectionLog())
            log.info("Disposing the session !!!");
        try {
            if (activeChannel != null) {
                // Clear agent channel references to prevent messages from closed channel
                handshakeAgent.setChannel(null);
                for (Agent agent: agents) {
                    agent.setChannel(null);
                }

                // Wait for channel to actually close
                activeChannel.close().sync();
                if (showConnectionLog())
                    log.info("Channel closed successfully");
            }
        } catch (InterruptedException e) {
            log.error("Interrupted while shutting down session", e);
            Thread.currentThread().interrupt();
        }
    }

    public void disableReconnection() {
        shouldReconnect.set(false);
    }

    public boolean shouldReconnect() {
        return shouldReconnect.get();
    }

    private boolean shouldRetry(long retriesUsed) {
        if (!shouldReconnect.get())
            return false;

        int maxRetryAttempts = config.getMaxRetryAttempts();
        return maxRetryAttempts == Integer.MAX_VALUE || retriesUsed < maxRetryAttempts;
    }

    public void handshake() {
        //Handshake First
        while (!handshakeAgent.isDone()) {
            if (handshakeAgent.hasAgency())
                handshakeAgent.sendNextMessage();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        if (log.isDebugEnabled())
            log.debug("Handshake successful");
    }

    private boolean showConnectionLog() {
        return config.isEnableConnectionLogging() &&
                (log.isDebugEnabled() || (handshakeAgent != null && !handshakeAgent.isSuppressConnectionInfoLog()));
    }
}
