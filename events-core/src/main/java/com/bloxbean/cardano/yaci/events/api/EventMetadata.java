package com.bloxbean.cardano.yaci.events.api;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable metadata attached to events providing context and traceability.
 * 
 * EventMetadata enriches events with contextual information that helps with:
 * - Debugging and tracing event flow through the system
 * - Correlating events with blockchain state
 * - Distinguishing live vs historical (replay) events
 * - Implementing retry and error handling logic
 * 
 * Chain coordinates (slot, blockNo, blockHash):
 * - Provide the blockchain position when the event occurred
 * - May be 0/null for non-blockchain events
 * - Essential for maintaining consistency during rollbacks
 * 
 * Replay flag:
 * - true: Historical event during catch-up sync
 * - false: Live event from real-time processing
 * - Allows different processing strategies for bulk vs live data
 * 
 * Delivery attempts:
 * - Tracks retry count for error recovery
 * - Helps implement exponential backoff
 * - Useful for dead-letter queue decisions
 */
public final class EventMetadata {
    private final Instant timestamp;        // When the event was created
    private final String origin;            // Component that created the event
    private final long slot;               // Cardano slot number (0 if N/A)
    private final long blockNo;            // Block number (0 if N/A)
    private final String blockHash;        // Block hash (null if N/A)
    private final boolean replay;          // true if historical, false if live
    private final String correlationId;    // For tracing related events
    private final int deliveryAttempt;     // Retry counter (starts at 1)

    private EventMetadata(Builder b) {
        this.timestamp = Objects.requireNonNullElseGet(b.timestamp, Instant::now);
        this.origin = b.origin;
        this.slot = b.slot;
        this.blockNo = b.blockNo;
        this.blockHash = b.blockHash;
        this.replay = b.replay;
        this.correlationId = b.correlationId;
        this.deliveryAttempt = b.deliveryAttempt;
    }

    public Instant timestamp() { return timestamp; }
    public String origin() { return origin; }
    public long slot() { return slot; }
    public long blockNo() { return blockNo; }
    public String blockHash() { return blockHash; }
    public boolean replay() { return replay; }
    public String correlationId() { return correlationId; }
    public int deliveryAttempt() { return deliveryAttempt; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Instant timestamp;
        private String origin;
        private long slot;
        private long blockNo;
        private String blockHash;
        private boolean replay;
        private String correlationId;
        private int deliveryAttempt;

        public Builder timestamp(Instant timestamp) { this.timestamp = timestamp; return this; }
        public Builder origin(String origin) { this.origin = origin; return this; }
        public Builder slot(long slot) { this.slot = slot; return this; }
        public Builder blockNo(long blockNo) { this.blockNo = blockNo; return this; }
        public Builder blockHash(String blockHash) { this.blockHash = blockHash; return this; }
        public Builder replay(boolean replay) { this.replay = replay; return this; }
        public Builder correlationId(String correlationId) { this.correlationId = correlationId; return this; }
        public Builder deliveryAttempt(int attempt) { this.deliveryAttempt = attempt; return this; }
        public EventMetadata build() { return new EventMetadata(this); }
    }
}

