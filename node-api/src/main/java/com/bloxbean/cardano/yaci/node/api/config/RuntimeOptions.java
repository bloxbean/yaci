package com.bloxbean.cardano.yaci.node.api.config;

import com.bloxbean.cardano.yaci.events.api.config.EventsOptions;

import java.util.Collections;
import java.util.Map;

public record RuntimeOptions(EventsOptions events, PluginsOptions plugins, Map<String, Object> globals) {
    public RuntimeOptions {
        events = events == null ? EventsOptions.defaults() : events;
        plugins = plugins == null ? PluginsOptions.defaults() : plugins;
        globals = globals == null ? Map.of() : Collections.unmodifiableMap(globals);
    }

    public static RuntimeOptions defaults() {
        return new RuntimeOptions(EventsOptions.defaults(), PluginsOptions.defaults(), Map.of());
    }
}
