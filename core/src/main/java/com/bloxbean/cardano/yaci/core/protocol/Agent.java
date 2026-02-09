package com.bloxbean.cardano.yaci.core.protocol;

import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public abstract class Agent<T extends AgentListener> {
    protected State currentState;
    private Instant instant;
    private volatile Channel channel;
    private final List<T> agentListeners = new ArrayList<>();
    private AcceptVersion acceptVersion;

    private final boolean isClient;

    public Agent(boolean isClient) {
        this.isClient = isClient;
    }

    public synchronized void setChannel(Channel channel) {
        if (this.channel != null && this.channel.isActive())
            log.warn("An active channel is already attached to this agent");

        this.channel = channel;
    }

    public void sendRequest(Message message) {
        if (currentState.hasAgency(isClient)) {
            if (log.isDebugEnabled())
                log.debug("Agency = true----------- Move to next state: agent for protocol id : " + this.getProtocolId());
            State oldState = currentState;
            currentState = currentState.nextState(message);
            if (log.isDebugEnabled())
                log.debug("Next state: " + currentState + " for protocol id: " + this.getProtocolId());
            // Log state transition for ChainSync
            if (this.getProtocolId() == 3 && log.isDebugEnabled()) {
                log.debug("Blockfetch state transition after sending {}: {} -> {}",
                        message.getClass().getSimpleName(), oldState, currentState);
            }
        }
    }

    public Message deserializeResponse(byte[] bytes) {
        return this.currentState.handleInbound(bytes);
    }

    public synchronized void receiveResponse(Message message) {
        State oldState = currentState;
        currentState = currentState.nextState(message);

        processResponse(message);
        //Notify
        getAgentListeners().forEach(agentListener -> agentListener.onStateUpdate(oldState, currentState));
    }

    public void sendNextMessage() {
        if (this.hasAgency()) {
            Message message = this.buildNextMessage();
            if (message == null)
                return;

            // Use the same segmentation logic as writeMessage
            writeMessage(message, () -> {
                // Update state only after message is successfully sent
                this.sendRequest(message);
            });
        }
    }

    protected void writeMessage(Message message, Runnable onSuccess) {
        Channel ch = this.channel; // volatile read, captured once
        if (ch == null || !ch.isActive()) {
            log.warn("Cannot write message: channel is null or inactive");
            return;
        }

        int protocolWithFlag = this.getProtocolId();
        if (log.isDebugEnabled()) {
            log.debug("Writing message for protocol id: {}, isClient: {}", protocolWithFlag, isClient);
        }
        if (!isClient) {
            protocolWithFlag |= 0x8000; // Server response flag
            if (log.isDebugEnabled()) {
                log.debug("Server mode: adding response flag - protocol {} -> {} (0x{})",
                         this.getProtocolId(), protocolWithFlag, Integer.toHexString(protocolWithFlag));
            }
        }

        // Check the base protocol ID without the response flag
        int baseProtocolId = protocolWithFlag & 0x7FFF;
        if (baseProtocolId < 0 || baseProtocolId > 100) {
            log.error("🚨 Suspicious protocol ID: {} (0x{}) (base: {} 0x{})",
                     protocolWithFlag, Integer.toHexString(protocolWithFlag),
                     baseProtocolId, Integer.toHexString(baseProtocolId));
        }

        byte[] payload = message.serialize();

        // Handle large payloads with automatic segmentation
        if (payload.length > 65535) {
            log.info("Large payload detected ({} bytes) - using mux-level segmentation", payload.length);
            writeSegmentedMessage(ch, protocolWithFlag, payload, onSuccess);
            return;
        }

        // Normal single segment message
        writeSingleSegment(ch, protocolWithFlag, payload, onSuccess);
    }

    private void writeSingleSegment(Channel ch, int protocolWithFlag, byte[] payload, Runnable onSuccess) {
        int elapseTime = calculateElapsedTime();

        Segment segment = Segment.builder()
                .timestamp(elapseTime)
                .protocol(protocolWithFlag)
                .payload(payload)
                .build();

        // Synchronize on channel to prevent concurrent writes from different agents
        synchronized (ch) {
            ch.writeAndFlush(segment).addListener(future -> {
                if (future.isSuccess()) {
                    if (onSuccess != null) onSuccess.run();
                } else {
                    log.error("Failed to send message for protocol {}: {}", getProtocolId(), future.cause().getMessage());
                }
            });
        }
    }

    private void writeSegmentedMessage(Channel ch, int protocolWithFlag, byte[] payload, Runnable onSuccess) {
        final int MAX_SEGMENT_SIZE = 65535;
        final int totalSegments = (payload.length + MAX_SEGMENT_SIZE - 1) / MAX_SEGMENT_SIZE;

        log.info("Segmenting large message: {} bytes into {} segments", payload.length, totalSegments);

        synchronized (ch) {
            for (int i = 0; i < totalSegments; i++) {
                int offset = i * MAX_SEGMENT_SIZE;
                int segmentSize = Math.min(MAX_SEGMENT_SIZE, payload.length - offset);

                byte[] segmentPayload = new byte[segmentSize];
                System.arraycopy(payload, offset, segmentPayload, 0, segmentSize);

                int elapseTime = calculateElapsedTime();

                Segment segment = Segment.builder()
                        .timestamp(elapseTime)
                        .protocol(protocolWithFlag)
                        .payload(segmentPayload)
                        .build();

                final boolean isLastSegment = (i == totalSegments - 1);
                final int segmentIndex = i;

                log.debug("Sending segment {}/{} - {} bytes", segmentIndex + 1, totalSegments, segmentSize);

                ch.writeAndFlush(segment).addListener(future -> {
                    if (future.isSuccess()) {
                        if (isLastSegment && onSuccess != null) {
                            onSuccess.run();
                        }
                        log.debug("Segment {}/{} sent successfully", segmentIndex + 1, totalSegments);
                    } else {
                        log.error("Failed to send segment {}/{} for protocol {}: {}",
                                 segmentIndex + 1, totalSegments, getProtocolId(), future.cause().getMessage());
                    }
                });
            }
        }
    }

    private int calculateElapsedTime() {
        if (instant == null) {
            instant = Instant.now();
            return 0; // First message has 0 elapsed time
        } else {
            // Calculate elapsed time in microseconds properly
            Duration elapsed = Duration.between(instant, Instant.now());
            instant = Instant.now();
            return (int) Math.min(elapsed.toNanos() / 1000, Integer.MAX_VALUE); // Convert to microseconds, cap at max int
        }
    }


    public final boolean hasAgency() {
        return currentState.hasAgency(isClient);
    }

    protected final boolean isClient() {
        return isClient;
    }

    /**
     * Add a listener to this agent. Listeners are executed in LIFO (Last In, First Out) order.
     * <p>
     * This means that listeners added later will execute before listeners added earlier.
     * This design ensures that:
     * <ul>
     *   <li>External application listeners (added later) execute first</li>
     *   <li>Internal protocol listeners (added during initialization) execute last</li>
     *   <li>Any failure in external listeners prevents protocol advancement</li>
     *   <li>Provides fail-fast semantics for atomic block processing</li>
     * </ul>
     *
     * @param agentListener the listener to add
     */
    public final synchronized void addListener(T agentListener) {
        agentListeners.add(0, agentListener);
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
    public void disconnected() {
        getAgentListeners().stream().forEach(agentListener -> agentListener.onDisconnect());
    }

    /**
     * This method is called during connection start or reset
     */
    public void reset() {

    }

    public void shutdown() {

    }

    public Channel getChannel() {
        return channel;
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
        return currentState;
    }

    public void onChannelWritabilityChanged(Channel channel) {

    }

    /**
     * Called when new blockchain data becomes available.
     * Agents can override this method to react to new blocks, rollbacks, etc.
     * Default implementation does nothing.
     */
    public void onNewDataAvailable() {
        // Default implementation - do nothing
        // Individual agents can override this to handle new data notifications
    }

    public abstract int getProtocolId();
    public abstract boolean isDone();
    protected abstract Message buildNextMessage();
    protected abstract void processResponse(Message message);
}
