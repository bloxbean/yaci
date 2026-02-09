package com.bloxbean.cardano.yaci.events.api;

/**
 * Options used at publish time.
 * Currently empty; reserved for future publish-time tuning.
 */
public final class PublishOptions {
    private PublishOptions() {}

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        public PublishOptions build() { return new PublishOptions(); }
    }
}
