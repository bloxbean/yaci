package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.network.NodeClient;
import com.bloxbean.cardano.yaci.core.network.NodeClientConfig;
import com.bloxbean.cardano.yaci.core.network.TCPNodeClient;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.N2NVersionData;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.Reason;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.core.protocol.peersharing.PeerSharingAgent;
import com.bloxbean.cardano.yaci.core.protocol.peersharing.PeerSharingAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.peersharing.messages.PeerAddress;
import com.bloxbean.cardano.yaci.helper.api.ReactiveFetcher;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

@Slf4j
public class PeerDiscovery extends ReactiveFetcher<List<PeerAddress>> {
    private final String host;
    private final int port;
    private final long protocolMagic;
    private HandshakeAgent handshakeAgent;
    private PeerSharingAgent peerSharingAgent;
    private NodeClient nodeClient;
    private VersionTable versionTable;
    private final String peerRequestKey = "PEER_REQUEST";
    private int requestAmount = PeerSharingAgent.DEFAULT_REQUEST_AMOUNT;
    private NodeClientConfig nodeClientConfig;

    private AcceptVersion acceptVersion;

    /**
     * Constructor with default settings optimized for peer discovery.
     * Uses 10-second connection timeout and disables auto-reconnect for fast failure.
     */
    public PeerDiscovery(String host, int port, long protocolMagic) {
        this(host, port, protocolMagic, PeerSharingAgent.DEFAULT_REQUEST_AMOUNT);
    }

    /**
     * Constructor with custom request amount and default connection settings.
     * Uses 10-second connection timeout and disables auto-reconnect for fast failure.
     */
    public PeerDiscovery(String host, int port, long protocolMagic, int requestAmount) {
        this(host, port, protocolMagic, requestAmount, createDefaultPeerDiscoveryConfig());
    }

    /**
     * Constructor with full control over connection behavior via NodeClientConfig.
     * This allows customization of connection timeout, auto-reconnect, retry attempts, etc.
     *
     * @param host the host to connect to
     * @param port the port to connect to
     * @param protocolMagic the protocol magic (network identifier)
     * @param requestAmount the number of peers to request
     * @param nodeClientConfig the connection configuration
     */
    public PeerDiscovery(String host, int port, long protocolMagic, int requestAmount, NodeClientConfig nodeClientConfig) {
        this.host = host;
        this.port = port;
        this.protocolMagic = protocolMagic;
        this.requestAmount = Math.min(Math.max(requestAmount, 1), PeerSharingAgent.MAX_REQUEST_AMOUNT);
        this.nodeClientConfig = nodeClientConfig;
        this.versionTable = N2NVersionTableConstant.v11AndAbove(protocolMagic, false, 1, false);
        init();
    }

    /**
     * Creates default NodeClientConfig optimized for peer discovery:
     * - Auto-reconnect disabled (fail fast)
     * - 10-second connection timeout (faster than default 30s)
     * - Connection logging enabled
     */
    private static NodeClientConfig createDefaultPeerDiscoveryConfig() {
        return NodeClientConfig.builder()
                .autoReconnect(false)
                .connectionTimeoutMs(10000)  // 10 seconds for peer discovery
                .build();
    }

    private void init() {
        handshakeAgent = new HandshakeAgent(versionTable);
        peerSharingAgent = new PeerSharingAgent();
        peerSharingAgent.setDefaultRequestAmount(requestAmount);

        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                if (log.isDebugEnabled()) {
                    log.debug("Handshake successful with {}:{}, starting peer discovery", host, port);
                }

                // Check if peer sharing is supported
                if (handshakeAgent.getProtocolVersion() != null &&
                    handshakeAgent.getProtocolVersion().getVersionData() instanceof N2NVersionData) {

                    N2NVersionData versionData =
                        (N2NVersionData) handshakeAgent.getProtocolVersion().getVersionData();

                    if (log.isDebugEnabled()) {
                        log.debug("Handshake completed with {}:{}", host, port);
                        log.debug("  Protocol Version: {}", handshakeAgent.getProtocolVersion().getVersionNumber());
                        log.debug("  Network Magic: {}", versionData.getNetworkMagic());
                        log.debug("  Initiator Only: {}", versionData.getInitiatorOnlyDiffusionMode());
                        log.debug("  Peer Sharing: {}", versionData.getPeerSharing());
                        log.debug("  Query Support: {}", versionData.getQuery());
                    }

                    if (versionData.getPeerSharing() == 0) {
                        log.warn("Peer sharing is disabled on remote node {}:{}", host, port);
                    }

                    acceptVersion = handshakeAgent.getProtocolVersion();
                } else {
                    log.warn("Could not determine peer sharing support for {}:{}", host, port);
                    acceptVersion = null;
                }

                peerSharingAgent.sendNextMessage();
            }

            @Override
            public void handshakeError(Reason reason) {
                log.error("Handshake failed with {}:{} - {}", host, port, reason);
            }
        });

        // Use the configured NodeClientConfig (optimized for peer discovery by default)
        nodeClient = new TCPNodeClient(host, port, nodeClientConfig, handshakeAgent, peerSharingAgent);
    }

    @Override
    public void start(Consumer<List<PeerAddress>> consumer) {
        peerSharingAgent.addListener(new PeerSharingAgentListener() {
            @Override
            public void peersReceived(List<PeerAddress> peerAddresses) {
                if (log.isDebugEnabled()) {
                    log.debug("Received {} peers from {}:{}", peerAddresses.size(), host, port);
                }
                if (consumer != null) {
                    consumer.accept(peerAddresses);
                }
            }

            @Override
            public void protocolCompleted() {
                if (log.isDebugEnabled()) {
                    log.debug("Peer sharing protocol completed with {}:{}", host, port);
                }
            }

            @Override
            public void error(String error) {
                log.error("Peer sharing error with {}:{} - {}", host, port, error);
            }
        });

        if (nodeClient != null) {
            nodeClient.start();
        }
    }

    public Mono<List<PeerAddress>> discover() {
        peerSharingAgent.addListener(new PeerSharingAgentListener() {
            @Override
            public void peersReceived(List<PeerAddress> peerAddresses) {
                applyMonoSuccess(peerRequestKey, peerAddresses);
            }

            @Override
            public void protocolCompleted() {
                if (log.isDebugEnabled()) {
                    log.debug("Peer sharing protocol completed");
                }
            }

            @Override
            public void error(String error) {
                applyError("Peer discovery failed: " + error);
            }
        });

        return Mono.create(peerMonoSink -> {
            if (log.isDebugEnabled()) {
                log.debug("Starting peer discovery from {}:{}", host, port);
            }
            storeMonoSinkReference(peerRequestKey, peerMonoSink);
            if (!nodeClient.isRunning()) {
                nodeClient.start();
            } else {
                peerSharingAgent.requestPeers(requestAmount);
            }
        });
    }

    public void requestMorePeers(int amount) {
        peerSharingAgent.requestPeers(amount);
    }

    public void shutdown() {
        if (nodeClient != null) {
            nodeClient.shutdown();
        }
    }

    @Override
    public boolean isRunning() {
        return nodeClient != null && nodeClient.isRunning();
    }

    public int getRequestAmount() {
        return requestAmount;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public Optional<AcceptVersion> getAcceptVersion() {
        return Optional.ofNullable(acceptVersion);
    }
}
