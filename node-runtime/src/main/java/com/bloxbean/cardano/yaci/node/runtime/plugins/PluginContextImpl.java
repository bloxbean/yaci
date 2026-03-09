package com.bloxbean.cardano.yaci.node.runtime.plugins;

import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.node.api.plugin.PluginContext;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;

final class PluginContextImpl implements PluginContext {
    private final EventBus eventBus;
    private final Logger logger;
    private final Map<String, Object> config;
    private final ScheduledExecutorService scheduler;
    private final Optional<ClassLoader> classLoader;
    private final Map<String, Object> registry = new ConcurrentHashMap<>();

    PluginContextImpl(EventBus eventBus, Logger logger, Map<String, Object> config,
                      ScheduledExecutorService scheduler, Optional<ClassLoader> classLoader) {
        this.eventBus = eventBus;
        this.logger = logger;
        this.config = config;
        this.scheduler = scheduler;
        this.classLoader = classLoader;
    }

    @Override public EventBus eventBus() { return eventBus; }
    @Override public Logger logger() { return logger; }
    @Override public Map<String, Object> config() { return config; }
    @Override public ScheduledExecutorService scheduler() { return scheduler; }
    @Override public Optional<ClassLoader> pluginClassLoader() { return classLoader; }

    @Override public void registerService(String key, Object service) { registry.put(key, service); }
    @Override public <T> Optional<T> getService(String key, Class<T> type) {
        Object obj = registry.get(key);
        if (obj == null) return Optional.empty();
        if (!type.isInstance(obj)) return Optional.empty();
        return Optional.of(type.cast(obj));
    }
}

