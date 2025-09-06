package com.bloxbean.cardano.yaci.events.api;

/** Options used at publish time. */
public final class PublishOptions {
    private final boolean async;
    private final int priority;

    private PublishOptions(Builder b) {
        this.async = b.async;
        this.priority = b.priority;
    }

    public boolean async() { return async; }
    public int priority() { return priority; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private boolean async;
        private int priority;
        public Builder async(boolean async) { this.async = async; return this; }
        public Builder priority(int priority) { this.priority = priority; return this; }
        public PublishOptions build() { return new PublishOptions(this); }
    }
}

