package com.bloxbean.cardano.yaci.node.app;

import com.bloxbean.cardano.yaci.node.api.NodeAPI;
import com.bloxbean.cardano.yaci.node.api.config.YaciNodeConfig;
import com.bloxbean.cardano.yaci.node.api.config.RuntimeOptions;
import com.bloxbean.cardano.yaci.node.api.config.PluginsOptions;
import com.bloxbean.cardano.yaci.events.api.config.EventsOptions;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yaci.node.runtime.YaciNode;
import io.quarkus.runtime.Shutdown;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CDI Producer for NodeAPI instance
 */
@ApplicationScoped
public class YaciNodeProducer {

    private static final Logger log = LoggerFactory.getLogger(YaciNodeProducer.class);

    @ConfigProperty(name = "yaci.node.network", defaultValue = "mainnet")
    String network;

    @ConfigProperty(name = "yaci.node.remote.host", defaultValue = "localhost")
    String remoteHost;

    @ConfigProperty(name = "yaci.node.remote.port", defaultValue = "32000")
    int remotePort;

    @ConfigProperty(name = "yaci.node.remote.protocol-magic", defaultValue = "1")
    long protocolMagic;

    @ConfigProperty(name = "yaci.node.server.port", defaultValue = "13337")
    int serverPort;

    @ConfigProperty(name = "yaci.node.client.enabled", defaultValue = "true")
    boolean clientEnabled;

    @ConfigProperty(name = "yaci.node.server.enabled", defaultValue = "true")
    boolean serverEnabled;

    @ConfigProperty(name = "yaci.node.storage.rocksdb", defaultValue = "true")
    boolean useRocksDB;

    @ConfigProperty(name = "yaci.node.storage.path", defaultValue = "./yaci-data")
    String storagePath;

    @ConfigProperty(name = "yaci.node.auto-sync-start", defaultValue = "false")
    boolean autoSyncStart;

    // Event/Plugin toggles
    @ConfigProperty(name = "yaci.events.enabled", defaultValue = "true")
    boolean eventsEnabled;
    @ConfigProperty(name = "yaci.plugins.enabled", defaultValue = "true")
    boolean pluginsEnabled;
    @ConfigProperty(name = "yaci.plugins.logging.enabled", defaultValue = "false")
    boolean loggingPluginEnabled;

    private NodeAPI nodeAPI;

    @Produces
    @ApplicationScoped
    public NodeAPI createNodeAPI() {
        if (nodeAPI == null) {
            log.info("Creating Yaci Node with network: {}", network);

            YaciNodeConfig config;
            switch (network.toLowerCase()) {
                case "mainnet":
                    config = YaciNodeConfig.mainnetDefault();
                    break;
                case "preprod":
                default:
                    config = YaciNodeConfig.preprodDefault();
                    break;
            }

            // Override with configuration properties
            config = YaciNodeConfig.builder()
                    .enableClient(clientEnabled)
                    .enableServer(serverEnabled)
                    .remoteHost(remoteHost)
                    .remotePort(remotePort)
                    .serverPort(serverPort)
                    .protocolMagic(protocolMagic)
                    .useRocksDB(useRocksDB)
                    .rocksDBPath(storagePath)
                    .fullSyncThreshold(config.getFullSyncThreshold())
                    .enablePipelinedSync(config.isEnablePipelinedSync())
                    .headerPipelineDepth(config.getHeaderPipelineDepth())
                    .bodyBatchSize(config.getBodyBatchSize())
                    .maxParallelBodies(config.getMaxParallelBodies())
                    .enableSelectiveBodyFetch(config.isEnableSelectiveBodyFetch())
                    .selectiveBodyFetchRatio(config.getSelectiveBodyFetchRatio())
                    .enableMonitoring(config.isEnableMonitoring())
                    .monitoringPort(config.getMonitoringPort())
                    .build();

            // Validate configuration
            config.validate();

            // Build explicit runtime options (no System properties)
            EventsOptions eventsOptions = new EventsOptions(eventsEnabled, 8192, SubscriptionOptions.Overflow.BLOCK);
            java.util.Map<String, Object> pluginConfig = new java.util.HashMap<>();
            pluginConfig.put("plugins.logging.enabled", loggingPluginEnabled);
            PluginsOptions pluginsOptions = new PluginsOptions(pluginsEnabled, false, java.util.Set.of(), java.util.Set.of(), pluginConfig);
            RuntimeOptions runtimeOptions = new RuntimeOptions(eventsOptions, pluginsOptions, java.util.Map.of());

            nodeAPI = new YaciNode(config, runtimeOptions);
            log.info("Yaci Node created successfully");

            //Register listeners
            //nodeAPI.registerListeners(new Listner1(), new Listener2());
        }

        return nodeAPI;
    }

    @Startup
    void onStartup() {
        log.info("Yaci Node Application starting up...");
        log.info("Auto-sync-start enabled: {}", autoSyncStart);

        if (autoSyncStart) {
            try {
                log.info("Auto-starting Yaci Node synchronization...");
                NodeAPI node = createNodeAPI();
                node.start();
                log.info("✅ Yaci Node started automatically and syncing with {} network", network);
                log.info("🌐 REST API available at http://localhost:8080/api/v1/node/ for manual control");
            } catch (Exception e) {
                log.error("❌ Failed to auto-start Yaci Node: {}", e.getMessage());
                log.info("💡 You can still start manually via: curl -X POST http://localhost:8080/api/v1/node/start");
            }
        } else {
            log.info("💡 Auto-sync is disabled. Start manually via: curl -X POST http://localhost:8080/api/v1/node/start");
            log.info("🌐 REST API available at http://localhost:8080/api/v1/node/");
        }
    }

    @Shutdown
    void onShutdown() {
        log.info("Yaci Node Application shutting down...");
        if (nodeAPI != null && nodeAPI.isRunning()) {
            log.info("Stopping Yaci Node...");
            nodeAPI.stop();
            log.info("Yaci Node stopped");
        }
    }
}
