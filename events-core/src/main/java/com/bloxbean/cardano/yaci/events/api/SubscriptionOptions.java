package com.bloxbean.cardano.yaci.events.api;

import java.util.concurrent.Executor;

/** Options for subscriptions. */
public final class SubscriptionOptions {
    public enum Overflow { BLOCK, DROP_LATEST, DROP_OLDEST, ERROR }

    private final int bufferSize;
    private final Overflow overflow;
    private final int concurrency; // 1 = ordered
    private final Executor executor; // null = synchronous
    private final EventFilter<?> filter;

    private SubscriptionOptions(Builder b) {
        this.bufferSize = b.bufferSize;
        this.overflow = b.overflow;
        this.concurrency = b.concurrency;
        this.executor = b.executor;
        this.filter = b.filter;
    }

    public int bufferSize() { return bufferSize; }
    public Overflow overflow() { return overflow; }
    public int concurrency() { return concurrency; }
    public Executor executor() { return executor; }
    @SuppressWarnings("unchecked")
    public <E extends Event> EventFilter<E> filter() { return (EventFilter<E>) filter; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private int bufferSize = 8192;
        private Overflow overflow = Overflow.BLOCK;
        private int concurrency = 1;
        private Executor executor;
        private EventFilter<?> filter;

        public Builder bufferSize(int bufferSize) { this.bufferSize = bufferSize; return this; }
        public Builder overflow(Overflow overflow) { this.overflow = overflow; return this; }
        public Builder concurrency(int concurrency) { this.concurrency = concurrency; return this; }
        public Builder executor(Executor executor) { this.executor = executor; return this; }
        public Builder filter(EventFilter<?> filter) { this.filter = filter; return this; }
        public SubscriptionOptions build() { return new SubscriptionOptions(this); }
    }
}

