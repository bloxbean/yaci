package com.bloxbean.cardano.yaci.events.api;

import java.util.concurrent.Executor;

/** Options for subscriptions. */
public final class SubscriptionOptions {
    public enum Overflow { BLOCK, DROP_LATEST, DROP_OLDEST, ERROR }

    private final int bufferSize;
    private final Overflow overflow;
    private final Executor executor; // null = synchronous
    private final EventFilter<?> filter;
    private final int priority; // lower runs earlier; default 0

    private SubscriptionOptions(Builder b) {
        this.bufferSize = b.bufferSize;
        this.overflow = b.overflow;
        this.executor = b.executor;
        this.filter = b.filter;
        this.priority = b.priority;
    }

    public int bufferSize() { return bufferSize; }
    public Overflow overflow() { return overflow; }
    public Executor executor() { return executor; }
    @SuppressWarnings("unchecked")
    public <E extends Event> EventFilter<E> filter() { return (EventFilter<E>) filter; }
    public int priority() { return priority; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private int bufferSize = 8192;
        private Overflow overflow = Overflow.BLOCK;
        private Executor executor;
        private EventFilter<?> filter;
        private int priority = 0;

        public Builder bufferSize(int bufferSize) { this.bufferSize = bufferSize; return this; }
        public Builder overflow(Overflow overflow) { this.overflow = overflow; return this; }
        public Builder executor(Executor executor) { this.executor = executor; return this; }
        public Builder filter(EventFilter<?> filter) { this.filter = filter; return this; }
        public Builder priority(int priority) { this.priority = priority; return this; }
        public SubscriptionOptions build() { return new SubscriptionOptions(this); }
    }
}
