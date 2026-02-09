package com.bloxbean.cardano.yaci.node.runtime.plugins;

import com.bloxbean.cardano.yaci.events.api.DomainEventListener;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yaci.events.api.support.AnnotationListenerRegistrar;
import com.bloxbean.cardano.yaci.node.api.plugin.NodePlugin;
import com.bloxbean.cardano.yaci.node.api.plugin.PluginCapability;
import com.bloxbean.cardano.yaci.node.api.plugin.PluginContext;
import com.bloxbean.cardano.yaci.node.runtime.events.BlockAppliedEvent;
import com.bloxbean.cardano.yaci.node.runtime.events.BlockReceivedEvent;
import com.bloxbean.cardano.yaci.node.runtime.events.RollbackEvent;
import com.bloxbean.cardano.yaci.node.runtime.events.SyncStatusChangedEvent;
import org.slf4j.Logger;

import java.util.List;
import java.util.Set;

/**
 * Built-in plugin that logs blockchain events for debugging and monitoring.
 * 
 * This plugin demonstrates the event-driven plugin architecture by subscribing
 * to all major blockchain events and logging them with consistent formatting.
 * It uses the annotation-based listener registration for clean, declarative code.
 * 
 * Features:
 * - Logs all block received/applied events with chain coordinates
 * - Tracks sync status changes (initial sync, live, catching up)
 * - Records rollback events with classification (real vs expected)
 * - Can be enabled/disabled via system property
 * 
 * Configuration:
 * - Enable: -Dyaci.plugins.logging.enabled=true
 * - Disable: -Dyaci.plugins.logging.enabled=false (default)
 * 
 * Log format:
 * All events are prefixed with [EVT] for easy filtering in log aggregators.
 * 
 * Example usage:
 * This plugin serves as a reference implementation for custom plugins.
 * Copy this pattern to create plugins that:
 * - Index blocks to databases
 * - Send notifications on specific events
 * - Collect metrics and statistics
 * - Implement custom validation logic
 * 
 * @see DomainEventListener for annotation-based event handling
 * @see AnnotationListenerRegistrar for automatic registration
 */
public final class LoggingPlugin implements NodePlugin {
    private Logger log;
    private List<com.bloxbean.cardano.yaci.events.api.SubscriptionHandle> handles;

    @Override public String id() { return "com.bloxbean.cardano.yaci.plugins.logging"; }
    @Override public String version() { return "1.0.0"; }
    @Override public Set<PluginCapability> capabilities() { return Set.of(PluginCapability.EVENT_CONSUMER); }

    @Override
    public void init(PluginContext ctx) {
        this.log = ctx.logger();
        Object val = ctx.config() != null ? ctx.config().get("plugins.logging.enabled") : null;
        boolean enabled = false;
        if (val instanceof Boolean b) enabled = b;
        else if (val instanceof String s) enabled = Boolean.parseBoolean(s);
        if (!enabled) {
            log.info("LoggingPlugin disabled via yaci.plugins.logging.enabled=false");
            this.handles = List.of();
            return;
        }
        SubscriptionOptions defaults = SubscriptionOptions.builder().build();
        this.handles = AnnotationListenerRegistrar.register(ctx.eventBus(), this, defaults);
        log.info("LoggingPlugin initialized; registered {} listeners", handles.size());
    }

    @Override public void start() {}
    @Override public void stop() { if (handles != null) handles.forEach(h -> { try { h.close(); } catch (Exception ignored) {} }); }
    @Override public void close() { stop(); }

    @DomainEventListener(order = 0)
    public void onBlockReceived(BlockReceivedEvent e) {
        log.info("[EVT] BlockReceived era={} slot={} no={} hash={}", e.era(), e.slot(), e.blockNumber(), e.blockHash());
    }

    @DomainEventListener(order = 1)
    public void onBlockApplied(BlockAppliedEvent e) {
        log.info("[EVT] BlockApplied era={} slot={} no={} hash={}", e.era(), e.slot(), e.blockNumber(), e.blockHash());
    }

    @DomainEventListener(order = 2)
    public void onRollback(RollbackEvent e) {
        log.info("[EVT] Rollback target={} realReorg={}", e.target(), e.realReorg());
    }

    @DomainEventListener(order = 3)
    public void onSyncStatus(SyncStatusChangedEvent e) {
        log.info("[EVT] SyncStatus {} -> {}", e.previous(), e.current());
    }
}
