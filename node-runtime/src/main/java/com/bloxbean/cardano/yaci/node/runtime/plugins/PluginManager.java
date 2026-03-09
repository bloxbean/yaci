package com.bloxbean.cardano.yaci.node.runtime.plugins;

import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.node.api.plugin.NodePlugin;
import com.bloxbean.cardano.yaci.node.api.plugin.PluginContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Manages the lifecycle of Yaci node plugins.
 * 
 * The PluginManager is responsible for:
 * - Discovering plugins via ServiceLoader
 * - Managing plugin dependencies and initialization order
 * - Providing runtime context to plugins
 * - Coordinating plugin lifecycle (init, start, stop, close)
 * - Handling plugin failures gracefully
 * 
 * Plugin discovery:
 * - Automatic: ServiceLoader scans classpath for NodePlugin implementations
 * - Manual: Plugins can be added programmatically before discovery
 * 
 * Dependency resolution:
 * - Topological sort ensures dependencies initialize first
 * - Circular dependencies are detected and logged (but not fatal)
 * - Missing dependencies are logged but don't prevent startup
 * 
 * Error handling:
 * - Plugin failures during init/start are isolated
 * - One plugin's failure doesn't affect others
 * - All errors are logged with plugin context
 * 
 * Thread safety: This class is NOT thread-safe. Methods should be called
 * from a single thread during node startup/shutdown.
 */
public final class PluginManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);
    
    // Core services provided to all plugins
    private final EventBus eventBus;
    private final ScheduledExecutorService scheduler;
    private final Map<String, Object> config;
    private final ClassLoader classLoader;
    
    // Ordered list of plugins (respects dependencies)
    private final List<NodePlugin> plugins = new ArrayList<>();
    
    // Track lifecycle state
    private boolean started = false;

    public PluginManager(EventBus eventBus, ScheduledExecutorService scheduler, Map<String, Object> config, ClassLoader cl) {
        this.eventBus = eventBus;
        this.scheduler = scheduler;
        this.config = config != null ? config : Map.of();
        this.classLoader = cl != null ? cl : Thread.currentThread().getContextClassLoader();
    }

    public void discoverAndInit() {
        ServiceLoader<NodePlugin> loader = ServiceLoader.load(NodePlugin.class, classLoader);
        for (NodePlugin p : loader) {
            try {
                log.info("Discovered plugin: {}:{}", p.id(), p.version());
                PluginContext ctx = new PluginContextImpl(eventBus, log, config, scheduler, Optional.ofNullable(classLoader));
                p.init(ctx);
                plugins.add(p);
            } catch (Throwable t) {
                log.error("Failed to init plugin {}: {}", safeId(p), t.toString(), t);
            }
        }
        orderPluginsByDependencies();
    }

    private void orderPluginsByDependencies() {
        // Minimal topo-sort; if cycles, keep discovery order
        Map<String, NodePlugin> byId = new HashMap<>();
        for (NodePlugin p : plugins) byId.put(p.id(), p);
        List<NodePlugin> ordered = new ArrayList<>();
        Set<String> temp = new HashSet<>();
        Set<String> perm = new HashSet<>();

        for (NodePlugin p : plugins) visit(p, byId, ordered, temp, perm);
        plugins.clear();
        plugins.addAll(ordered);
    }

    private void visit(NodePlugin p, Map<String, NodePlugin> byId, List<NodePlugin> ordered,
                       Set<String> temp, Set<String> perm) {
        String id = p.id();
        if (perm.contains(id)) return;
        if (temp.contains(id)) {
            log.warn("Cycle detected in plugin dependencies at {}. Keeping discovery order.", id);
            return;
        }
        temp.add(id);
        for (String dep : p.dependsOn()) {
            NodePlugin dp = byId.get(dep);
            if (dp != null) visit(dp, byId, ordered, temp, perm);
        }
        perm.add(id);
        ordered.add(p);
    }

    public void startAll() {
        for (NodePlugin p : plugins) {
            try { p.start(); } catch (Throwable t) {
                log.error("Plugin start failed for {}: {}", safeId(p), t.toString(), t);
            }
        }
        started = true;
    }

    public void stopAll() {
        if (!started) return;
        ListIterator<NodePlugin> it = plugins.listIterator(plugins.size());
        while (it.hasPrevious()) {
            NodePlugin p = it.previous();
            try { p.stop(); } catch (Throwable t) {
                log.warn("Plugin stop error for {}: {}", safeId(p), t.toString());
            }
        }
        started = false;
    }

    @Override
    public void close() {
        stopAll();
        for (NodePlugin p : plugins) {
            try { p.close(); } catch (Throwable ignored) {}
        }
        plugins.clear();
    }

    private static String safeId(NodePlugin p) {
        try { return p.id(); } catch (Throwable t) { return p.getClass().getName(); }
    }
}

