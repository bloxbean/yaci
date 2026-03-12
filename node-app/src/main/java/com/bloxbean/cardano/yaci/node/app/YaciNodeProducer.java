package com.bloxbean.cardano.yaci.node.app;

import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yaci.events.api.config.EventsOptions;
import com.bloxbean.cardano.yaci.node.api.NodeAPI;
import com.bloxbean.cardano.yaci.node.api.config.PeerType;
import com.bloxbean.cardano.yaci.node.api.config.PluginsOptions;
import com.bloxbean.cardano.yaci.node.api.config.RuntimeOptions;
import com.bloxbean.cardano.yaci.node.api.config.UpstreamConfig;
import com.bloxbean.cardano.yaci.node.api.config.YaciNodeConfig;
import com.bloxbean.cardano.yaci.node.app.bootstrap.BootstrapConfigParser;
import com.bloxbean.cardano.client.common.model.SlotConfig;
import com.bloxbean.cardano.client.common.model.SlotConfigs;
import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.node.ledgerrules.TransactionEvaluator;
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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    @ConfigProperty(name = "quarkus.http.port", defaultValue = "8080")
    int httpPort;

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

    // Block body pruning config
    @ConfigProperty(name = "yaci.node.chain.block-body-prune-depth", defaultValue = "0")
    int blockBodyPruneDepth;
    @ConfigProperty(name = "yaci.node.chain.block-prune-batch-size", defaultValue = "500000")
    int blockPruneBatchSize;
    @ConfigProperty(name = "yaci.node.chain.block-prune-interval-seconds", defaultValue = "300")
    long blockPruneIntervalSeconds;

    // UTXO storage filter config
    @ConfigProperty(name = "yaci.node.filters.utxo.enabled", defaultValue = "false")
    boolean utxoFilterEnabled;
    @ConfigProperty(name = "yaci.node.filters.utxo.addresses")
    java.util.Optional<java.util.List<String>> utxoFilterAddresses;
    @ConfigProperty(name = "yaci.node.filters.utxo.payment-credentials")
    java.util.Optional<java.util.List<String>> utxoFilterPaymentCredentials;

    // Dev mode
    @ConfigProperty(name = "yaci.node.dev-mode", defaultValue = "false")
    boolean devMode;

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

    // App-layer messaging config
    @ConfigProperty(name = "yaci.node.app-layer.enabled", defaultValue = "false")
    boolean appLayerEnabled;

    @ConfigProperty(name = "yaci.node.app-layer.auth-mode", defaultValue = "open")
    String appLayerAuthMode;

    @ConfigProperty(name = "yaci.node.app-layer.allowed-keys")
    java.util.Optional<java.util.List<String>> appLayerAllowedKeys;

    @ConfigProperty(name = "yaci.node.app-layer.mempool-max-size", defaultValue = "1000")
    int appMessageMemPoolMaxSize;

    @ConfigProperty(name = "yaci.node.app-layer.default-ttl-seconds", defaultValue = "600")
    long appMessageDefaultTtlSeconds;

    @ConfigProperty(name = "yaci.node.app-layer.consensus-mode", defaultValue = "single-signer")
    String appConsensusMode;

    @ConfigProperty(name = "yaci.node.app-layer.block-interval-ms", defaultValue = "5000")
    int appBlockIntervalMs;

    @ConfigProperty(name = "yaci.node.app-layer.consensus-threshold", defaultValue = "1")
    int appConsensusThreshold;

    @ConfigProperty(name = "yaci.node.app-layer.consensus-total-signers", defaultValue = "1")
    int appConsensusTotalSigners;

    @ConfigProperty(name = "yaci.node.app-layer.ledger-enabled", defaultValue = "true")
    boolean appLedgerEnabled;

    @ConfigProperty(name = "yaci.node.app-layer.ledger-path")
    java.util.Optional<String> appLedgerPath;

    @ConfigProperty(name = "yaci.node.app-layer.topics")
    java.util.Optional<java.util.List<String>> appTopics;

    // Bootstrap config
    @ConfigProperty(name = "yaci.node.bootstrap.enabled", defaultValue = "false")
    boolean bootstrapEnabled;

    @ConfigProperty(name = "yaci.node.bootstrap.block-number", defaultValue = "-1")
    long bootstrapBlockNumber;

    @ConfigProperty(name = "yaci.node.bootstrap.provider", defaultValue = "blockfrost")
    String bootstrapProvider;

    @ConfigProperty(name = "yaci.node.bootstrap.addresses")
    java.util.Optional<java.util.List<String>> bootstrapAddresses;

    @ConfigProperty(name = "yaci.node.bootstrap.utxos")
    java.util.Optional<java.util.List<String>> bootstrapUtxos;

    @ConfigProperty(name = "yaci.node.bootstrap.blockfrost.api-key")
    java.util.Optional<String> bootstrapBlockfrostApiKey;

    @ConfigProperty(name = "yaci.node.bootstrap.blockfrost.base-url")
    java.util.Optional<String> bootstrapBlockfrostBaseUrl;

    @ConfigProperty(name = "yaci.node.bootstrap.koios.base-url")
    java.util.Optional<String> bootstrapKoiosBaseUrl;

    // Peer discovery config
    @ConfigProperty(name = "yaci.node.peer-discovery.enabled", defaultValue = "false")
    boolean peerDiscoveryEnabled;

    @ConfigProperty(name = "yaci.node.peer-discovery.interval-seconds", defaultValue = "60")
    int peerDiscoveryIntervalSeconds;

    @ConfigProperty(name = "yaci.node.peer-discovery.max-peers", defaultValue = "20")
    int peerDiscoveryMaxPeers;

    // Genesis config (shared between devnet and relay modes)
    @ConfigProperty(name = "yaci.node.genesis.shelley-genesis-file")
    java.util.Optional<String> shelleyGenesisFile;

    @ConfigProperty(name = "yaci.node.genesis.byron-genesis-file")
    java.util.Optional<String> byronGenesisFile;

    @ConfigProperty(name = "yaci.node.genesis.alonzo-genesis-file")
    java.util.Optional<String> alonzoGenesisFile;

    @ConfigProperty(name = "yaci.node.genesis.conway-genesis-file")
    java.util.Optional<String> conwayGenesisFile;

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

        // Resolve genesis files: user config takes precedence, then auto-resolve from bundled classpath resources
        String resolvedShelleyGenesis = resolveGenesisFile(shelleyGenesisFile.orElse(null), protocolMagic, "shelley-genesis.json");
        String resolvedByronGenesis = resolveGenesisFile(byronGenesisFile.orElse(null), protocolMagic, "byron-genesis.json");
        String resolvedAlonzoGenesis = resolveGenesisFile(alonzoGenesisFile.orElse(null), protocolMagic, "alonzo-genesis.json");
        String resolvedConwayGenesis = resolveGenesisFile(conwayGenesisFile.orElse(null), protocolMagic, "conway-genesis.json");

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
                .devMode(devMode)
                .blockTimeMillis(blockTimeMillis)
                .lazyBlockProduction(blockProducerLazy)
                .genesisTimestamp(genesisTimestamp)
                .slotLengthMillis(slotLengthMillis)
                .shelleyGenesisFile(resolvedShelleyGenesis)
                .byronGenesisFile(resolvedByronGenesis)
                .alonzoGenesisFile(resolvedAlonzoGenesis)
                .conwayGenesisFile(resolvedConwayGenesis)
                .protocolParametersFile(protocolParametersFile.orElse(null))
                .enableBootstrap(bootstrapEnabled)
                .bootstrapBlockNumber(bootstrapBlockNumber)
                .bootstrapProvider(bootstrapProvider)
                .bootstrapAddresses(bootstrapAddresses.orElse(null))
                .bootstrapUtxos(BootstrapConfigParser.parseUtxoRefs(bootstrapUtxos.orElse(null)))
                .bootstrapBlockfrostApiKey(bootstrapBlockfrostApiKey.orElse(null))
                .bootstrapBlockfrostBaseUrl(bootstrapBlockfrostBaseUrl.orElse(null))
                .bootstrapKoiosBaseUrl(bootstrapKoiosBaseUrl.orElse(null))
                .network(network)
                .upstreams(parseUpstreams())
                .peerDiscoveryEnabled(peerDiscoveryEnabled)
                .peerDiscoveryIntervalSeconds(peerDiscoveryIntervalSeconds)
                .peerDiscoveryMaxPeers(peerDiscoveryMaxPeers)
                .enableAppLayer(appLayerEnabled)
                .appLayerAuthMode(appLayerAuthMode)
                .appLayerAllowedKeys(appLayerAllowedKeys.orElse(java.util.List.of()))
                .appMessageMemPoolMaxSize(appMessageMemPoolMaxSize)
                .appMessageDefaultTtlSeconds(appMessageDefaultTtlSeconds)
                .appConsensusMode(appConsensusMode)
                .appBlockIntervalMs(appBlockIntervalMs)
                .appConsensusThreshold(appConsensusThreshold)
                .appConsensusTotalSigners(appConsensusTotalSigners)
                .appLedgerEnabled(appLedgerEnabled)
                .appLedgerPath(appLedgerPath.orElse(null))
                .appTopics(appTopics.orElse(java.util.List.of()))
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

        // Block pruning
        globals.put("yaci.node.chain.block-body-prune-depth", blockBodyPruneDepth);
        globals.put("yaci.node.chain.block-prune-batch-size", blockPruneBatchSize);
        globals.put("yaci.node.chain.block-prune-interval-seconds", blockPruneIntervalSeconds);

        // UTXO filters
        globals.put("yaci.node.filters.utxo.enabled", utxoFilterEnabled);
        globals.put("yaci.node.filters.utxo.addresses", utxoFilterAddresses.orElse(java.util.List.of()));
        globals.put("yaci.node.filters.utxo.payment-credentials", utxoFilterPaymentCredentials.orElse(java.util.List.of()));

        RuntimeOptions runtimeOptions = new RuntimeOptions(eventsOptions, pluginsOptions, globals);

        // Set plugin classloader on thread context so PluginManager picks it up
        Thread.currentThread().setContextClassLoader(pluginClassLoader);

        nodeAPI = new YaciNode(yaciConfig, runtimeOptions);
        log.info("Yaci Node created successfully");

        // Wire bootstrap data provider if bootstrap is enabled
        if (bootstrapEnabled) {
            wireBootstrapProvider((YaciNode) nodeAPI, yaciConfig);
        }

        // Initialize transaction evaluator if enabled
        if (txEvaluationEnabled) {
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
                log.info("REST API available at http://localhost:{}/api/v1/node/ for manual control", httpPort);
            } catch (Exception e) {
                log.error("Failed to auto-start Yaci Node: {}", e.getMessage(), e);
                if (bootstrapEnabled) {
                    log.error("Bootstrap mode is enabled but failed. "
                            + "The node cannot start without bootstrap state. Shutting down.");
                    throw new RuntimeException("Bootstrap failed, cannot start node", e);
                }
                log.info("You can still start manually via: curl -X POST http://localhost:{}/api/v1/node/start", httpPort);
            }
        } else {
            log.info("Auto-sync is disabled. Start manually via: curl -X POST http://localhost:{}/api/v1/node/start", httpPort);
            log.info("REST API available at http://localhost:{}/api/v1/node/", httpPort);
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

            // Also create a script evaluator for the /utils/txs/evaluate endpoint
            TransactionEvaluator scriptEvaluator =
                    ScalusTransactionValidatorFactory.createEvaluator(pp, slotConfig, networkId);
            yaciNode.setScriptEvaluator(scriptEvaluator);

            log.info("Transaction evaluator initialized (networkId={})", networkId);
        } catch (Exception e) {
            log.warn("Failed to initialize transaction evaluator: {}", e.getMessage());
        }
    }

    /**
     * Wire the bootstrap data provider into the YaciNode based on configuration.
     */
    private void wireBootstrapProvider(YaciNode yaciNode, YaciNodeConfig yaciConfig) {
        try {
            String providerType = yaciConfig.getBootstrapProvider() != null
                    ? yaciConfig.getBootstrapProvider().toLowerCase() : "blockfrost";
            String net = yaciConfig.getNetwork() != null ? yaciConfig.getNetwork() : "preprod";

            com.bloxbean.cardano.yaci.node.api.bootstrap.BootstrapDataProvider provider;
            switch (providerType) {
                case "koios" -> {
                    if (yaciConfig.getBootstrapKoiosBaseUrl() != null
                            && !yaciConfig.getBootstrapKoiosBaseUrl().isBlank()) {
                        provider = new com.bloxbean.cardano.yaci.node.bootstrap.providers.KoiosBootstrapProvider(
                                yaciConfig.getBootstrapKoiosBaseUrl());
                    } else {
                        provider = com.bloxbean.cardano.yaci.node.bootstrap.providers.KoiosBootstrapProvider
                                .forNetwork(net);
                    }
                }
                default -> { // blockfrost
                    String apiKey = yaciConfig.getBootstrapBlockfrostApiKey();
                    if (apiKey == null || apiKey.isBlank()) {
                        log.warn("Bootstrap enabled but no Blockfrost API key configured. "
                                + "Set yaci.node.bootstrap.blockfrost.api-key");
                        return;
                    }
                    if (yaciConfig.getBootstrapBlockfrostBaseUrl() != null
                            && !yaciConfig.getBootstrapBlockfrostBaseUrl().isBlank()) {
                        provider = new com.bloxbean.cardano.yaci.node.bootstrap.providers.BlockfrostBootstrapProvider(
                                yaciConfig.getBootstrapBlockfrostBaseUrl(), apiKey);
                    } else {
                        provider = com.bloxbean.cardano.yaci.node.bootstrap.providers.BlockfrostBootstrapProvider
                                .forNetwork(net, apiKey);
                    }
                }
            }
            yaciNode.setBootstrapDataProvider(provider);
            log.info("Bootstrap data provider configured: {}", providerType);
        } catch (Exception e) {
            log.error("Failed to configure bootstrap provider: {}", e.getMessage());
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

    /**
     * Resolve a genesis file path. If the user has provided an explicit path and the file exists,
     * use it. Otherwise, for known networks (mainnet/preprod/preview), extract the bundled
     * classpath resource to a temp file.
     */
    private String resolveGenesisFile(String userPath, long magic, String filename) {
        // If user provided an explicit path and file exists, use it
        if (userPath != null && !userPath.isBlank()) {
            if (new java.io.File(userPath).exists()) {
                return userPath;
            }
            log.debug("User-configured genesis file not found: {}, trying bundled resource", userPath);
        }

        // Auto-resolve from bundled classpath resources based on protocol magic
        String networkDir = networkDirForMagic(magic);
        if (networkDir == null) {
            return userPath; // Unknown network, can't auto-resolve
        }

        String classpathResource = "genesis/" + networkDir + "/" + filename;
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(classpathResource)) {
            if (is == null) {
                log.debug("Bundled genesis resource not found: {}", classpathResource);
                return userPath;
            }
            // Extract to temp file
            Path tempFile = Files.createTempFile("yaci-" + networkDir + "-", "-" + filename);
            tempFile.toFile().deleteOnExit();
            Files.copy(is, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("Auto-resolved {} from bundled resource for {} network", filename, networkDir);
            return tempFile.toString();
        } catch (IOException e) {
            log.warn("Failed to extract bundled genesis resource {}: {}", classpathResource, e.getMessage());
            return userPath;
        }
    }

    /**
     * Parse upstream peer list from Quarkus indexed config properties.
     * Format: yaci.node.upstreams[0].host, yaci.node.upstreams[0].port, yaci.node.upstreams[0].type
     */
    private List<UpstreamConfig> parseUpstreams() {
        List<UpstreamConfig> result = new ArrayList<>();
        var config = org.eclipse.microprofile.config.ConfigProvider.getConfig();

        for (int i = 0; i < 50; i++) { // support up to 50 upstreams
            String prefix = "yaci.node.upstreams[" + i + "]";
            var host = config.getOptionalValue(prefix + ".host", String.class);
            if (host.isEmpty()) break;

            int port = config.getOptionalValue(prefix + ".port", Integer.class).orElse(3001);
            String typeStr = config.getOptionalValue(prefix + ".type", String.class).orElse("cardano");
            PeerType type;
            try {
                type = PeerType.valueOf(typeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                type = PeerType.CARDANO;
            }

            result.add(UpstreamConfig.builder()
                    .host(host.get())
                    .port(port)
                    .type(type)
                    .build());
        }
        return result;
    }

    private static String networkDirForMagic(long magic) {
        if (magic == Constants.MAINNET_PROTOCOL_MAGIC) return "mainnet";
        if (magic == Constants.PREPROD_PROTOCOL_MAGIC) return "preprod";
        if (magic == Constants.PREVIEW_PROTOCOL_MAGIC) return "preview";
        return null;
    }
}
