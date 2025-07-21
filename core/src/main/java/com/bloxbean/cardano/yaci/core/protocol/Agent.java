package com.bloxbean.cardano.yaci.core.protocol;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.RollForward;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public abstract class Agent<T extends AgentListener> {
    protected State currenState;
    private Instant instant;
    private Channel channel;
    private final List<T> agentListeners = new ArrayList<>();
    private AcceptVersion acceptVersion;

    private final boolean isClient;

    public Agent(boolean isClient) {
        this.isClient = isClient;
    }

    public void setChannel(Channel channel) {
        if (this.channel != null && this.channel.isActive())
            log.warn("An active channel is already attached to this agent");

        this.channel = channel;
    }

    public void sendRequest(Message message) {
        if (currenState.hasAgency(isClient)) {
            if (log.isDebugEnabled())
                log.debug("Agency = true----------- Move to next state: agent for protocol id : " + this.getProtocolId());
            State oldState = currenState;
            currenState = currenState.nextState(message);
            if (log.isDebugEnabled())
                log.debug("Next state: " + currenState + " for protocol id: " + this.getProtocolId());
            // Log state transition for ChainSync
            if (this.getProtocolId() == 3 && log.isDebugEnabled()) {
                log.debug("Blockfetch state transition after sending {}: {} -> {}",
                        message.getClass().getSimpleName(), oldState, currenState);
            }
        } else {
            //TODO
//            log.info("Agency = false-----------");
        }
    }

    public Message deserializeResponse(byte[] bytes) {
        return this.currenState.handleInbound(bytes);
    }

    public synchronized final void receiveResponse(Message message) {
        State oldState = currenState;
        currenState = currenState.nextState(message);

        processResponse(message);
        //Notify
        getAgentListeners().forEach(agentListener -> agentListener.onStateUpdate(oldState, currenState));
    }

    public final void sendNextMessage() {
        if (this.hasAgency()) {
            Message message = this.buildNextMessage();
            if (message == null)
                return;

            if (instant == null)
                instant = Instant.now();

            int elapseTime = Duration.between(instant, Instant.now()).getNano() / 1000;
            instant = Instant.now();

            // Apply response flag (0x8000) for server agents
            int protocolWithFlag = this.getProtocolId();
            if (!isClient) {
                protocolWithFlag |= 0x8000; // Set response flag for server
            }

            Segment segment = Segment.builder()
                    .timestamp(elapseTime)
                    .protocol((short) protocolWithFlag)
                    .payload(message.serialize())
                    .build();

            log.info("Sending msg : " + message);
            if (message instanceof RollForward) {
                log.info("RollForward message: " + ((RollForward)message).getOriginalHeaderBytes());
            }
//            try {
//                if (segment.getPayload().length > 0) {
//                    log.info("Protocol Id: " + segment.getProtocol() + " Payload : " + CborSerializationUtil.deserializeOne(segment.getPayload()));
//                } else {
//                    log.info("Protocol Id: " + segment.getProtocol() + " Payload : <empty>");
//                }
//            } catch (Exception e) {
//                log.warn("Protocol Id: " + segment.getProtocol() + " Payload : <failed to deserialize - " + e.getMessage() + ">");
//            }

            channel.writeAndFlush(segment).addListener(future -> {;
                if (future.isSuccess()) {
//                    log.info("Message sent successfully for protocol id: " + this.getProtocolId());
                    // Update state only after message is successfully sent
                    State oldState = currenState;
                    this.sendRequest(message);

                    // If agent still has agency after state transition, send next message
                    // This is crucial for BlockFetch Streaming state where server keeps agency
//                    if (this.hasAgency() && oldState != currenState) {
//                        if (log.isDebugEnabled()) {
//                            log.debug("Agent {} still has agency after state transition {} -> {}, sending next message",
//                                    this.getProtocolId(), oldState, currenState);
//                        }
//                        this.sendNextMessage();
//                    }
                    // Special case for BlockFetch: continue sending in Streaming state even without state change
                    if (this.hasAgency() && this.getProtocolId() == 3 && currenState.toString().equals("Streaming")) {
                        if (log.isDebugEnabled()) {
                            log.debug("Agent {} (BlockFetch) continuing in Streaming state after {}, sending next message",
                                    this.getProtocolId(), message.getClass().getSimpleName());
                        }
                        this.sendNextMessage();
                    }
                } else {
                    log.info(message.toString());
                    log.error("Failed to send message for protocol id: " + this.getProtocolId(), future.cause());
                }
            });
        }
    }

    public final boolean hasAgency() {
        return currenState.hasAgency(isClient);
    }

    protected final boolean isClient() {
        return isClient;
    }

    public final synchronized void addListener(T agentListener) {
        agentListeners.add(agentListener);
    }

    public final synchronized void removeListener(T agentListener) {
        agentListeners.remove(agentListener);
    }

    protected List<T> getAgentListeners() {
        return agentListeners;
    }

    /**
     * This method is called after disconnection and during connection retry failure.
     */
    public final void disconnected() {
        getAgentListeners().stream().forEach(agentListener -> agentListener.onDisconnect());
    }

    /**
     * This method is called during connection start or reset
     */
    public void reset() {

    }

    public void shutdown() {

    }

    /**
     * Accepted version during hashshake.
     * This method should not be called directly by the application. It is invoked by the NodeClient during successful
     * connection
     * @param version
     */
    public void setProtocolVersion(AcceptVersion version) {
        this.acceptVersion = version;
    }

    /**
     * Returns the version number accepted during handshake
     * @return version
     */
    public AcceptVersion getProtocolVersion() {
        return acceptVersion;
    }

    public State getCurrentState() {
        return currenState;
    }

    public abstract int getProtocolId();
    public abstract boolean isDone();
    protected abstract Message buildNextMessage();
    protected abstract void processResponse(Message message);
}
