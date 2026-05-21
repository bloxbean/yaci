package com.bloxbean.cardano.yaci.core.network;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import lombok.extern.slf4j.Slf4j;

import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * This class tries to open the network connection. The session gets destroyed during disconnection event.
 */
@Slf4j
class Session implements Disposable {
    private final SocketAddressProvider socketAddressProvider;
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
     * @param socketAddressProvider the socket address provider to use for each connection attempt cycle
     * @param clientBootstrap the Netty bootstrap
     * @param config the connection configuration
     * @param handshakeAgent the handshake agent
     * @param agents the protocol agents
     */
    public Session(SocketAddressProvider socketAddressProvider, Bootstrap clientBootstrap, NodeClientConfig config,
                   HandshakeAgent handshakeAgent, Agent[] agents) {
        this.socketAddressProvider = socketAddressProvider;
        this.clientBootstrap = clientBootstrap;
        this.config = config != null ? config : NodeClientConfig.defaultConfig();
        this.shouldReconnect = new AtomicBoolean(this.config.isAutoReconnect());

        this.handshakeAgent = handshakeAgent;
        this.agents = agents;
    }

    /**
     * Constructor with NodeClientConfig for configurable connection behavior.
     *
     * @param socketAddressSupplier the socket address supplier to use for each connection attempt cycle
     * @param clientBootstrap the Netty bootstrap
     * @param config the connection configuration
     * @param handshakeAgent the handshake agent
     * @param agents the protocol agents
     */
    public Session(Supplier<SocketAddress> socketAddressSupplier, Bootstrap clientBootstrap, NodeClientConfig config,
                   HandshakeAgent handshakeAgent, Agent[] agents) {
        this(() -> List.of(socketAddressSupplier.get()), clientBootstrap, config, handshakeAgent, agents);
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
        long retryCyclesUsed = 0;
        long attempts = 0;

        while (connectFuture == null) {
            Exception lastFailure = null;
            List<SocketAddress> socketAddresses = null;

            try {
                socketAddresses = socketAddressProvider.get();
                if (socketAddresses == null || socketAddresses.isEmpty()) {
                    throw new IllegalStateException("No socket addresses available for connection");
                }
            } catch (Exception e) {
                lastFailure = e;
                logAddressResolutionFailure(e);
            }

            if (socketAddresses != null) {
                for (int i = 0; i < socketAddresses.size() && connectFuture == null; i++) {
                    SocketAddress socketAddress = socketAddresses.get(i);
                    try {
                        attempts++;
                        if (showConnectionLog())
                            log.info("Connecting to {} (attempt {}, candidate {}/{}, max retries: {})",
                                    socketAddress, attempts, i + 1, socketAddresses.size(),
                                    config.getMaxRetryAttempts());
                        connectFuture = clientBootstrap.connect(socketAddress).sync();
                    } catch (InterruptedException e) {
                        throw e;
                    } catch (Exception e) {
                        lastFailure = e;
                        logConnectionFailure(socketAddress, e);
                    }
                }
            }

            if (connectFuture == null) {
                if (!shouldRetry(retryCyclesUsed)) {
                    logConnectionCycleFailure("Connection failed after exhausting socket address candidates",
                            lastFailure);
                    throwConnectionFailure(lastFailure);
                }

                retryCyclesUsed++;
                Thread.sleep(config.getInitialRetryDelayMs());
                if (lastFailure != null)
                    log.warn("Connection cycle failed. Retrying after {} ms: {}", config.getInitialRetryDelayMs(),
                            lastFailure.toString());
                else
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

    private void throwConnectionFailure(Exception failure) throws InterruptedException {
        if (failure == null)
            throw new IllegalStateException("Connection failed");

        if (failure instanceof InterruptedException)
            throw (InterruptedException) failure;

        if (failure instanceof RuntimeException)
            throw (RuntimeException) failure;

        throw new RuntimeException(failure);
    }

    private void logConnectionFailure(SocketAddress socketAddress, Exception failure) {
        if (log.isDebugEnabled()) {
            log.warn("Connection failed for {}", socketAddress, failure);
        } else {
            log.warn("Connection failed for {}: {}", socketAddress, failure.toString());
        }
    }

    private void logAddressResolutionFailure(Exception failure) {
        if (log.isDebugEnabled()) {
            log.warn("Address resolution failed", failure);
        } else {
            log.warn("Address resolution failed: {}", failure.toString());
        }
    }

    private void logConnectionCycleFailure(String message, Exception failure) {
        if (failure == null) {
            log.error(message);
        } else {
            log.error(message, failure);
        }
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
