package com.bloxbean.cardano.yaci.core.network;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import lombok.extern.slf4j.Slf4j;

import java.net.SocketAddress;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
class Session implements Disposable {
    private final SocketAddress socketAddress;
    private final EventLoopGroup scheduler;
    private final Bootstrap clientBootstrap;

    private final Instant instant;
    private int reconnectDelayMs;
    private Channel activeChannel;
    private final AtomicBoolean shouldReconnect;
    private final Agent handshakeAgent;
    private final Agent[] agents;


    public Session(SocketAddress socketAddress, Bootstrap clientBootstrap, EventLoopGroup scheduler, Agent handshakeAgent, Agent[] agents) {
        this.socketAddress = socketAddress;
        this.scheduler = scheduler;
        this.clientBootstrap = clientBootstrap;
        this.reconnectDelayMs = 1; //It was 1
        this.shouldReconnect = new AtomicBoolean(true);

        this.handshakeAgent = handshakeAgent;
        this.agents = agents;

        this.instant = Instant.now();
    }

    public Disposable start() throws InterruptedException {
        for (Agent agent: agents) {
            agent.reset();
            agent.reset();
        }

        //Create a new connectFuture
        ChannelFuture connectFuture = clientBootstrap.connect(socketAddress).sync();
        activeChannel = connectFuture.channel();

        handshakeAgent.setChannel(activeChannel);
        for (Agent agent: agents) {
            agent.setChannel(activeChannel);
        }

        connectFuture.addListeners((ChannelFuture cf) -> {
            if (cf.isSuccess()) {
                log.info("\nConnection established");
                reconnectDelayMs = 1;

                //Listen to the channel closing
                var closeFuture = activeChannel.closeFuture();
                closeFuture.addListeners((ChannelFuture closeFut) -> {
                    if (shouldReconnect.get()) {
                        log.info("Trying to reconnect ....");
                        activeChannel.eventLoop().schedule(this::start, nextReconnectDelay(), TimeUnit.MILLISECONDS);
                    } else {
                        log.info("Session has been disposed won't reconnect");
                    }
                });

            } else {
                int delay = nextReconnectDelay();
                log.info(String.format("Connection failed will re-attempt in %s ms", delay));
                cf.channel().eventLoop().schedule(this::start, delay, TimeUnit.MILLISECONDS);
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
        log.info("Disposing the session");
        try {
            shouldReconnect.set(false);
            scheduler.shutdownGracefully().sync();
            if (activeChannel != null) {
                activeChannel.closeFuture().sync();
            }
        } catch (InterruptedException e) {
            log.error("Interrupted while shutting down TcpClient");
        }
    }

    private int nextReconnectDelay() {
        this.reconnectDelayMs = this.reconnectDelayMs * 2;
        return Math.min(this.reconnectDelayMs, 5000);
    }

    public void handshake() {
        //Handshake First
        while (!handshakeAgent.isDone()) {
            if (handshakeAgent.hasAgency())
//                sendNextMessage(handshakeAgent);
                handshakeAgent.sendNextMessage();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        log.info("Handshake successful");
    }

//    public void runAgent() {
//        while(true) {
//            int count = 0;
//            for (Agent agent : agents) {
//                if (!agent.isDone()) {
//                    count++;
//                    if (!agent.isDone() && agent.hasAgency()) {
//                        sendNextMessage(agent);
//                    }
//                }
//            }
//
//            if (count == 0) {
//                log.info("No live agent !!!");
//                break;
//            }
//        }
//        log.info("Outside agent.isDone ......");
//
//        dispose();
//    }
//
//    private void sendNextMessage(Agent agent) {
//        Message message = agent.buildNextMessage();
//        if (message == null)
//            return;
//
//        Segment segment = new Segment();
//        int elapseTime = Duration.between(instant, Instant.now()).getNano() / 1000;
//        instant = Instant.now();
//        segment.setTimestamp(elapseTime);
//        segment.setProtocol((short) agent.getProtocolId());
//        segment.setPayload(message.serialize());
//
//        activeChannel.writeAndFlush(segment);
//        agent.sendRequest(message);
//    }
}
