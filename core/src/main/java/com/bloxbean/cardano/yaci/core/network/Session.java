package com.bloxbean.cardano.yaci.core.network;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import lombok.extern.slf4j.Slf4j;

import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This class tries to open the network connection. The session gets destroyed during disconnection event.
 */
@Slf4j
class Session implements Disposable {
    private final SocketAddress socketAddress;
    private final Bootstrap clientBootstrap;
    private Channel activeChannel;
    private final AtomicBoolean shouldReconnect; //Not used currently
    private final HandshakeAgent handshakeAgent;
    private final Agent[] agents;

    private SessionListener sessionListener;

    public Session(SocketAddress socketAddress, Bootstrap clientBootstrap, HandshakeAgent handshakeAgent, Agent[] agents) {
        this.socketAddress = socketAddress;
        this.clientBootstrap = clientBootstrap;
        this.shouldReconnect = new AtomicBoolean(true);

        this.handshakeAgent = handshakeAgent;
        this.agents = agents;
    }

    public void setSessionListener(SessionListener sessionListener) {
        if (this.sessionListener != null)
            throw new RuntimeException("SessionListener is already there. Can't set another SessionListener");

        this.sessionListener = sessionListener;
    }

    public Disposable start() throws InterruptedException {
        //Create a new connectFuture
        ChannelFuture connectFuture = null;
        while (connectFuture == null && shouldReconnect.get()) {
            try {
                connectFuture = clientBootstrap.connect(socketAddress).sync();
            } catch (Exception e) {
                log.error("Connection failed", e);
                Thread.sleep(8000);
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
        return log.isDebugEnabled() || (handshakeAgent != null && !handshakeAgent.isSuppressConnectionInfoLog());
    }
}
