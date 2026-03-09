package com.bloxbean.cardano.yaci.node.api.config;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

public record PluginsOptions(
        boolean enabled,
        boolean autoRegisterAnnotated,
        Set<String> allowList,
        Set<String> denyList,
        Map<String, Object> config
) {
    public PluginsOptions {
        allowList = allowList == null ? Set.of() : Collections.unmodifiableSet(allowList);
        denyList = denyList == null ? Set.of() : Collections.unmodifiableSet(denyList);
        config = config == null ? Map.of() : Collections.unmodifiableMap(config);
    }

    public static PluginsOptions defaults() {
        return new PluginsOptions(true, false, Set.of(), Set.of(), Map.of());
    }
}
