package com.bloxbean.cardano.yaci.node.app;

import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yaci.events.api.config.EventsOptions;
import com.bloxbean.cardano.yaci.node.api.NodeAPI;
import com.bloxbean.cardano.yaci.node.api.config.PluginsOptions;
import com.bloxbean.cardano.yaci.node.api.config.RuntimeOptions;
import com.bloxbean.cardano.yaci.node.api.config.YaciNodeConfig;
import com.bloxbean.cardano.client.common.model.SlotConfig;
import com.bloxbean.cardano.client.common.model.SlotConfigs;
import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.node.ledgerrules.TransactionValidator;
import com.bloxbean.cardano.yaci.node.runtime.YaciNode;
import com.bloxbean.cardano.yaci.node.runtime.blockproducer.GenesisConfig;
import com.bloxbean.cardano.yaci.node.runtime.blockproducer.ProtocolParamsMapper;
import com.bloxbean.cardano.yaci.node.scalusbridge.ScalusTransactionValidatorFactory;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class YaciNodeProducer {

    private static final Logger log = LoggerFactory.getLogger(YaciNodeProducer.class);

    @ConfigProperty(name = "yaci.node.network", defaultValue = "mainnet")
    String network;

    @ConfigProperty(name = "yaci.node.remote.host", defaultValue = "backbone.cardano.iog.io")
    String remoteHost;

    @ConfigProperty(name = "yaci.node.remote.port", defaultValue = "3001")
    int remotePort;

    @ConfigProperty(name = "yaci.node.remote.protocol-magic", defaultValue = "764824073")
    long protocolMagic;

    @ConfigProperty(name = "yaci.node.server.port", defaultValue = "13337")
    int serverPort;

    @ConfigProperty(name = "yaci.node.client.enabled", defaultValue = "true")
    boolean clientEnabled;

    @ConfigProperty(name = "yaci.node.server.enabled", defaultValue = "true")
    boolean serverEnabled;

    @ConfigProperty(name = "yaci.node.storage.rocksdb", defaultValue = "true")
    boolean useRocksDB;

    @ConfigProperty(name = "yaci.node.storage.path", defaultValue = "./chainstate")
    String storagePath;

    @ConfigProperty(name = "yaci.node.auto-sync-start", defaultValue = "false")
    boolean autoSyncStart;

    @ConfigProperty(name = "yaci.events.enabled", defaultValue = "true")
    boolean eventsEnabled;

    @ConfigProperty(name = "yaci.plugins.enabled", defaultValue = "true")
    boolean pluginsEnabled;

    @ConfigProperty(name = "yaci.plugins.logging.enabled", defaultValue = "false")
    boolean pluginsLoggingEnabled;

    // UTXO config
    @ConfigProperty(name = "yaci.node.utxo.enabled", defaultValue = "true")
    boolean utxoEnabled;
    @ConfigProperty(name = "yaci.node.utxo.pruneDepth", defaultValue = "2160")
    int utxoPruneDepth;
    @ConfigProperty(name = "yaci.node.utxo.rollbackWindow", defaultValue = "4320")
    int utxoRollbackWindow;
    @ConfigProperty(name = "yaci.node.utxo.pruneBatchSize", defaultValue = "500")
    int utxoPruneBatchSize;
    @ConfigProperty(name = "yaci.node.utxo.index.address_hash", defaultValue = "true")
    boolean utxoIndexAddressHash;
    @ConfigProperty(name = "yaci.node.utxo.index.payment_credential", defaultValue = "true")
    boolean utxoIndexPaymentCred;
    @ConfigProperty(name = "yaci.node.utxo.indexingStrategy", defaultValue = "both")
    String utxoIndexingStrategy;
    @ConfigProperty(name = "yaci.node.utxo.delta.selfContained", defaultValue = "false")
    boolean utxoDeltaSelfContained;
    @ConfigProperty(name = "yaci.node.utxo.applyAsync", defaultValue = "false")
    boolean utxoApplyAsync;

    // Block producer config
    @ConfigProperty(name = "yaci.node.block-producer.enabled", defaultValue = "false")
    boolean blockProducerEnabled;

    @ConfigProperty(name = "yaci.node.block-producer.block-time-millis", defaultValue = "2000")
    int blockTimeMillis;

    @ConfigProperty(name = "yaci.node.block-producer.lazy", defaultValue = "false")
    boolean blockProducerLazy;

    @ConfigProperty(name = "yaci.node.block-producer.genesis-timestamp", defaultValue = "0")
    long genesisTimestamp;

    @ConfigProperty(name = "yaci.node.block-producer.slot-length-millis", defaultValue = "1000")
    int slotLengthMillis;

    @ConfigProperty(name = "yaci.node.block-producer.tx-evaluation", defaultValue = "true")
    boolean txEvaluationEnabled;

    // Genesis config (shared between devnet and relay modes)
    @ConfigProperty(name = "yaci.node.genesis.shelley-genesis-file")
    java.util.Optional<String> shelleyGenesisFile;

    @ConfigProperty(name = "yaci.node.genesis.byron-genesis-file")
    java.util.Optional<String> byronGenesisFile;

    @ConfigProperty(name = "yaci.node.genesis.protocol-parameters-file")
    java.util.Optional<String> protocolParametersFile;

    private final ClassLoader pluginClassLoader;
    private NodeAPI nodeAPI;

    public YaciNodeProducer(@Named("pluginClassLoader") ClassLoader pluginClassLoader) {
        this.pluginClassLoader = pluginClassLoader;
    }

    @Produces
    @ApplicationScoped
    public NodeAPI createNodeAPI() {
        if (nodeAPI != null) {
            return nodeAPI;
        }

        log.info("Creating Yaci Node with network: {}", network);

        YaciNodeConfig yaciConfig;
        switch (network.toLowerCase()) {
            case "mainnet":
                yaciConfig = YaciNodeConfig.mainnetDefault();
                break;
            case "preprod":
            default:
                yaciConfig = YaciNodeConfig.preprodDefault();
                break;
        }

        // Override with configuration properties
        yaciConfig = YaciNodeConfig.builder()
                .enableClient(clientEnabled)
                .enableServer(serverEnabled)
                .remoteHost(remoteHost)
                .remotePort(remotePort)
                .serverPort(serverPort)
                .protocolMagic(protocolMagic)
                .useRocksDB(useRocksDB)
                .rocksDBPath(storagePath)
                .fullSyncThreshold(yaciConfig.getFullSyncThreshold())
                .enablePipelinedSync(yaciConfig.isEnablePipelinedSync())
                .headerPipelineDepth(yaciConfig.getHeaderPipelineDepth())
                .bodyBatchSize(yaciConfig.getBodyBatchSize())
                .maxParallelBodies(yaciConfig.getMaxParallelBodies())
                .enableSelectiveBodyFetch(yaciConfig.isEnableSelectiveBodyFetch())
                .selectiveBodyFetchRatio(yaciConfig.getSelectiveBodyFetchRatio())
                .enableMonitoring(yaciConfig.isEnableMonitoring())
                .monitoringPort(yaciConfig.getMonitoringPort())
                .enableBlockProducer(blockProducerEnabled)
                .blockTimeMillis(blockTimeMillis)
                .lazyBlockProduction(blockProducerLazy)
                .genesisTimestamp(genesisTimestamp)
                .slotLengthMillis(slotLengthMillis)
                .shelleyGenesisFile(shelleyGenesisFile.orElse(null))
                .byronGenesisFile(byronGenesisFile.orElse(null))
                .protocolParametersFile(protocolParametersFile.orElse(null))
                .build();

        // Validate configuration
        yaciConfig.validate();

        // Build runtime options
        EventsOptions eventsOptions = new EventsOptions(
                eventsEnabled, 8192, SubscriptionOptions.Overflow.BLOCK);

        Map<String, Object> pluginConfigMap = new HashMap<>();
        pluginConfigMap.put("plugins.logging.enabled", pluginsLoggingEnabled);
        PluginsOptions pluginsOptions = new PluginsOptions(
                pluginsEnabled, false, Set.of(), Set.of(), pluginConfigMap);

        // Globals: UTXO options
        Map<String, Object> globals = new HashMap<>();
        globals.put("yaci.node.utxo.enabled", utxoEnabled);
        globals.put("yaci.node.utxo.pruneDepth", utxoPruneDepth);
        globals.put("yaci.node.utxo.rollbackWindow", utxoRollbackWindow);
        globals.put("yaci.node.utxo.pruneBatchSize", utxoPruneBatchSize);
        globals.put("yaci.node.utxo.index.address_hash", utxoIndexAddressHash);
        globals.put("yaci.node.utxo.index.payment_credential", utxoIndexPaymentCred);
        globals.put("yaci.node.utxo.indexingStrategy", utxoIndexingStrategy);
        globals.put("yaci.node.utxo.delta.selfContained", utxoDeltaSelfContained);
        globals.put("yaci.node.utxo.applyAsync", utxoApplyAsync);
        globals.put("yaci.node.tx-evaluation.enabled", txEvaluationEnabled);

        RuntimeOptions runtimeOptions = new RuntimeOptions(eventsOptions, pluginsOptions, globals);

        // Set plugin classloader on thread context so PluginManager picks it up
        Thread.currentThread().setContextClassLoader(pluginClassLoader);

        nodeAPI = new YaciNode(yaciConfig, runtimeOptions);
        log.info("Yaci Node created successfully");

        // Initialize transaction evaluator if enabled
        if (txEvaluationEnabled && blockProducerEnabled) {
            initTransactionEvaluator((YaciNode) nodeAPI, yaciConfig);
        }

        return nodeAPI;
    }

    void onStart(@Observes StartupEvent event) {
        log.info("Yaci Node Application starting up...");
        log.info("Auto-sync-start enabled: {}", autoSyncStart);

        if (autoSyncStart) {
            try {
                log.info("Auto-starting Yaci Node synchronization...");
                NodeAPI node = createNodeAPI();
                node.start();
                log.info("Yaci Node started automatically and syncing with {} network", network);
                log.info("REST API available at http://localhost:8080/api/v1/node/ for manual control");
            } catch (Exception e) {
                log.error("Failed to auto-start Yaci Node: {}", e.getMessage());
                log.info("You can still start manually via: curl -X POST http://localhost:8080/api/v1/node/start");
            }
        } else {
            log.info("Auto-sync is disabled. Start manually via: curl -X POST http://localhost:8080/api/v1/node/start");
            log.info("REST API available at http://localhost:8080/api/v1/node/");
        }
    }

    /**
     * Initialize the Scalus-based transaction evaluator and inject it into the node.
     */
    private void initTransactionEvaluator(YaciNode yaciNode, YaciNodeConfig yaciConfig) {
        try {
            GenesisConfig genesis = GenesisConfig.load(
                    yaciConfig.getShelleyGenesisFile(),
                    yaciConfig.getByronGenesisFile(),
                    yaciConfig.getProtocolParametersFile());

            if (genesis == null || !genesis.hasProtocolParameters()) {
                log.info("Transaction evaluation not available: no protocol parameters");
                return;
            }

            com.bloxbean.cardano.client.api.model.ProtocolParams pp =
                    ProtocolParamsMapper.fromNodeProtocolParam(genesis.getProtocolParameters());

            long magic = yaciConfig.getProtocolMagic();
            SlotConfig slotConfig;
            //TODO -- We should be able to derive slot config from genesis data instead of hardcoding for mainnet/preprod/preview
            // Only gap is how to determine zero slot which depends on shelley start slot.
            if (magic == Constants.MAINNET_PROTOCOL_MAGIC) {
                slotConfig = SlotConfigs.mainnet();
            } else if (magic == Constants.PREPROD_PROTOCOL_MAGIC) {
                slotConfig = SlotConfigs.preprod();
            } else if (magic == Constants.PREVIEW_PROTOCOL_MAGIC) {
                slotConfig = SlotConfigs.preview();
            } else {
                long genesisTs = yaciConfig.getGenesisTimestamp() > 0
                        ? yaciConfig.getGenesisTimestamp()
                        : genesis.getSystemStartEpochMillis() > 0
                                ? genesis.getSystemStartEpochMillis()
                                : System.currentTimeMillis();
                slotConfig = new SlotConfig(yaciConfig.getSlotLengthMillis(), 0, genesisTs);
            }

            int networkId = magic == Constants.MAINNET_PROTOCOL_MAGIC ? 1 : 0;

            TransactionValidator evaluator =
                    ScalusTransactionValidatorFactory.create(pp, slotConfig, networkId);
            yaciNode.setTransactionEvaluator(evaluator);

            log.info("Transaction evaluator initialized (networkId={})", networkId);
        } catch (Exception e) {
            log.warn("Failed to initialize transaction evaluator: {}", e.getMessage());
        }
    }

    void onStop(@Observes ShutdownEvent event) {
        log.info("Yaci Node Application shutting down...");
        if (nodeAPI != null && nodeAPI.isRunning()) {
            log.info("Stopping Yaci Node...");
            nodeAPI.stop();
            log.info("Yaci Node stopped");
        }
    }
}
