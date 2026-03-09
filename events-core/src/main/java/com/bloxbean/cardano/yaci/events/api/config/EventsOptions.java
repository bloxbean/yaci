package com.bloxbean.cardano.yaci.events.api.config;

import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;

public record EventsOptions(boolean enabled, int bufferSize, SubscriptionOptions.Overflow overflow) {
    public static EventsOptions defaults() {
        return new EventsOptions(true, 8192, SubscriptionOptions.Overflow.BLOCK);
    }
}
