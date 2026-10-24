package com.bloxbean.cardano.yaci.core.protocol;

import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
public abstract class Agent<T extends AgentListener> {
    protected volatile State currentState;
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

    public synchronized void sendRequest(Message message) {
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
        if (baseProtocolId < 0 || baseProtocolId > 199) {
            log.error("🚨 Suspicious protocol ID: {} (0x{}) (base: {} 0x{})",
                     protocolWithFlag, Integer.toHexString(protocolWithFlag),
                     baseProtocolId, Integer.toHexString(baseProtocolId));
        }

        byte[] payload = message.serialize();
        List<Segment> segments = toSegments(protocolWithFlag, payload);

        // The segments are written on the channel's event loop, without holding a lock: completing a write can run
        // the listeners of earlier writes on the writing thread, and onSuccess takes the agent lock. A writer that
        // held the channel lock there would invert the agent -> channel order of writers that hold the agent lock
        // (e.g. ChainSyncServerAgent.onNewDataAvailable) and deadlock both threads. Netty runs event-loop tasks in
        // order, so the segments of one message reach the channel together and messages keep their order.
        EventLoop eventLoop = ch.eventLoop();
        if (eventLoop.inEventLoop()) {
            writeSegments(ch, segments, onSuccess);
        } else {
            try {
                eventLoop.execute(() -> writeSegments(ch, segments, onSuccess));
            } catch (RejectedExecutionException e) {
                log.warn("Cannot write message for protocol {}: event loop is shut down", getProtocolId());
            }
        }
    }

    private List<Segment> toSegments(int protocolWithFlag, byte[] payload) {
        final int MAX_SEGMENT_SIZE = 65535;

        // Handle large payloads with automatic segmentation
        if (payload.length <= MAX_SEGMENT_SIZE)
            return List.of(segment(protocolWithFlag, payload));

        final int totalSegments = (payload.length + MAX_SEGMENT_SIZE - 1) / MAX_SEGMENT_SIZE;
        log.info("Segmenting large message: {} bytes into {} segments", payload.length, totalSegments);

        List<Segment> segments = new ArrayList<>(totalSegments);
        for (int i = 0; i < totalSegments; i++) {
            int offset = i * MAX_SEGMENT_SIZE;
            int segmentSize = Math.min(MAX_SEGMENT_SIZE, payload.length - offset);

            byte[] segmentPayload = new byte[segmentSize];
            System.arraycopy(payload, offset, segmentPayload, 0, segmentSize);
            segments.add(segment(protocolWithFlag, segmentPayload));
        }
        return segments;
    }

    private Segment segment(int protocolWithFlag, byte[] payload) {
        return Segment.builder()
                .timestamp(calculateElapsedTime())
                .protocol(protocolWithFlag)
                .payload(payload)
                .build();
    }

    // Called on the event loop only. onSuccess runs once, after the last segment is written.
    private void writeSegments(Channel ch, List<Segment> segments, Runnable onSuccess) {
        int totalSegments = segments.size();
        for (int i = 0; i < totalSegments; i++) {
            final boolean isLastSegment = (i == totalSegments - 1);
            final int segmentIndex = i;
            ch.write(segments.get(i)).addListener(future -> {
                if (future.isSuccess()) {
                    if (isLastSegment && onSuccess != null) {
                        onSuccess.run();
                    }
                    log.debug("Segment {}/{} sent successfully", segmentIndex + 1, totalSegments);
                } else {
                    log.error("Failed to send segment {}/{} for protocol {}",
                            segmentIndex + 1, totalSegments, getProtocolId(), future.cause());
                }
            });
        }
        ch.flush();
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
     * Returns true if the underlying Netty channel is non-null and currently active.
     */
    public boolean isChannelActive() {
        Channel ch = this.channel;
        return ch != null && ch.isActive();
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
