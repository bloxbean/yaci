package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.network.NodeClientConfig;
import com.bloxbean.cardano.yaci.core.network.TCPNodeClient;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.N2NVersionData;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.Reason;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionData;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.core.protocol.keepalive.KeepAliveAgent;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosPoint;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosRawCbor;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosTxBitmap;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.LeiosFetchAgent;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.LeiosFetchAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.LeiosNotifyAgent;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.LeiosNotifyAgentListener;
import com.bloxbean.cardano.yaci.helper.listener.LeiosDataListener;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Slf4j
public class LeiosNetworkClient implements AutoCloseable {
    private static final int KEEP_ALIVE_INTERVAL_SECONDS = 10;
    private static final long GRACEFUL_SHUTDOWN_TIMEOUT_MILLIS = 2_000;

    private final String host;
    private final int port;
    private final long networkMagic;
    private final NodeClientConfig nodeClientConfig;
    private final HandshakeAgent handshakeAgent;
    private final KeepAliveAgent keepAliveAgent;
    private final LeiosNotifyAgent leiosNotifyAgent;
    private final LeiosFetchAgent leiosFetchAgent;
    private final TCPNodeClient nodeClient;
    private final List<LeiosDataListener> dataListeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean disconnectNotified = new AtomicBoolean(false);
    private final AtomicBoolean clientClosing = new AtomicBoolean(false);
    private final ScheduledExecutorService keepAliveExecutor;

    private volatile AcceptVersion protocolVersion;
    private volatile boolean leiosActive;
    private volatile boolean handshakeCompleted;
    private volatile ScheduledFuture<?> keepAliveFuture;

    public LeiosNetworkClient(String host, int port) {
        this(host, port, Constants.MUSASHI_PROTOCOL_MAGIC);
    }

    public LeiosNetworkClient(String host, int port, long networkMagic) {
        this(host, port, networkMagic, leiosVersionTable(networkMagic));
    }

    public LeiosNetworkClient(String host, int port, long networkMagic, VersionTable versionTable) {
        this(host, port, networkMagic, versionTable, NodeClientConfig.defaultConfig());
    }

    public LeiosNetworkClient(String host, int port, long networkMagic,
                              VersionTable versionTable, NodeClientConfig nodeClientConfig) {
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
        this.networkMagic = networkMagic;
        this.nodeClientConfig = nodeClientConfig != null ? nodeClientConfig : NodeClientConfig.defaultConfig();

        VersionTable effectiveVersionTable = Objects.requireNonNull(versionTable, "versionTable");
        this.handshakeAgent = new HandshakeAgent(effectiveVersionTable);
        this.keepAliveAgent = new KeepAliveAgent();
        this.leiosNotifyAgent = new LeiosNotifyAgent();
        this.leiosFetchAgent = new LeiosFetchAgent();
        this.keepAliveExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "yaci-leios-keepalive-" + host + ":" + port);
            thread.setDaemon(true);
            return thread;
        });
        this.nodeClient = new TCPNodeClient(
                host, port, this.nodeClientConfig, handshakeAgent, keepAliveAgent,
                leiosNotifyAgent, leiosFetchAgent);

        setupListeners();
    }

    public void start() {
        clientClosing.set(false);
        nodeClient.start();
    }

    public void shutdown() {
        clientClosing.set(true);
        leiosActive = false;
        stopKeepAliveTimer();
        leiosNotifyAgent.shutdown();
        leiosFetchAgent.done();
        waitForGracefulMiniProtocolShutdown();
        keepAliveAgent.shutdown();
        nodeClient.shutdown();
        keepAliveExecutor.shutdownNow();
    }

    @Override
    public void close() {
        shutdown();
    }

    public boolean isRunning() {
        return nodeClient.isRunning();
    }

    public boolean isLeiosActive() {
        return leiosActive;
    }

    public Optional<AcceptVersion> getProtocolVersion() {
        return Optional.ofNullable(protocolVersion);
    }

    public long getNetworkMagic() {
        return networkMagic;
    }

    LeiosNotifyAgent getLeiosNotifyAgent() {
        return leiosNotifyAgent;
    }

    LeiosFetchAgent getLeiosFetchAgent() {
        return leiosFetchAgent;
    }

    public void addDataListener(LeiosDataListener listener) {
        if (listener != null) {
            dataListeners.add(listener);
        }
    }

    public void addNotifyListener(LeiosNotifyAgentListener listener) {
        if (listener != null) {
            leiosNotifyAgent.addListener(listener);
        }
    }

    public void addFetchListener(LeiosFetchAgentListener listener) {
        if (listener != null) {
            leiosFetchAgent.addListener(listener);
        }
    }

    public void requestBlock(LeiosPoint point) {
        ensureActive();
        leiosFetchAgent.requestBlock(point);
    }

    public void requestBlockTxs(LeiosPoint point, LeiosTxBitmap bitmap) {
        ensureActive();
        if (bitmap.isEmpty()) {
            throw new IllegalArgumentException("Empty Leios tx bitmap requests are disabled until inbound mux " +
                    "CBOR byte-fidelity is fixed");
        }
        leiosFetchAgent.requestBlockTxs(point, bitmap);
    }

    public void requestFirstBlockTxs(LeiosPoint point, int txCount) {
        requestBlockTxs(point, LeiosTxBitmap.firstN(txCount));
    }

    public void sendKeepAliveMessage(int cookie) {
        keepAliveAgent.sendKeepAlive(cookie);
    }

    void handleHandshakeComplete(AcceptVersion acceptVersion) {
        this.protocolVersion = acceptVersion;
        this.handshakeCompleted = true;
        this.clientClosing.set(false);
        disconnectNotified.set(false);
        dispatchDataListener(listener -> listener.onHandshake(acceptVersion), "onHandshake");

        if (isLeiosCompatible(acceptVersion)) {
            leiosActive = true;
            startKeepAliveTimer();
            leiosNotifyAgent.start();
            dispatchDataListener(listener -> listener.onLeiosActivated(acceptVersion), "onLeiosActivated");
            log.info("Leios mini-protocols activated for {}:{} with {}", host, port, acceptVersion);
        } else {
            leiosActive = false;
            leiosNotifyAgent.stopAutoRequestNext();
            stopKeepAliveTimer();
            dispatchDataListener(listener -> listener.onLeiosNotActivated(acceptVersion), "onLeiosNotActivated");
            log.info("Leios mini-protocols not activated for {}:{} with {}", host, port, acceptVersion);
        }
    }

    private void setupListeners() {
        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                handleHandshakeComplete(handshakeAgent.getProtocolVersion());
            }

            @Override
            public void handshakeError(Reason reason) {
                leiosActive = false;
                stopKeepAliveTimer();
                dispatchDataListener(listener -> listener.onHandshakeError(reason), "onHandshakeError");
            }
        });

        leiosNotifyAgent.addListener(new LeiosNotifyAgentListener() {
            @Override
            public void onBlockAnnouncement(LeiosRawCbor announcement) {
                dispatchDataListener(listener -> listener.onBlockAnnouncement(announcement),
                        "onBlockAnnouncement");
            }

            @Override
            public void onBlockOffer(LeiosPoint point, long ebSize) {
                dispatchDataListener(listener -> listener.onBlockOffer(point, ebSize), "onBlockOffer");
            }

            @Override
            public void onBlockTxsOffer(LeiosPoint point) {
                dispatchDataListener(listener -> listener.onBlockTxsOffer(point), "onBlockTxsOffer");
            }

            @Override
            public void onVotes(List<LeiosRawCbor> votes) {
                dispatchDataListener(listener -> listener.onVotes(votes), "onVotes");
            }

            @Override
            public void onNotifyError(Throwable error) {
                dispatchDataListener(listener -> listener.onNotifyError(error), "onNotifyError");
            }

            @Override
            public void onDisconnect() {
                handleDisconnect();
            }
        });

        leiosFetchAgent.addListener(new LeiosFetchAgentListener() {
            @Override
            public void onBlock(LeiosPoint requestedPoint, LeiosRawCbor endorserBlock) {
                dispatchDataListener(listener -> listener.onBlock(requestedPoint, endorserBlock), "onBlock");
            }

            @Override
            public void onBlockTxs(LeiosPoint requestedPoint, LeiosPoint responsePoint,
                                   LeiosTxBitmap responseBitmap, LeiosRawCbor txList) {
                dispatchDataListener(listener ->
                        listener.onBlockTxs(requestedPoint, responsePoint, responseBitmap, txList), "onBlockTxs");
            }

            @Override
            public void onFetchError(Throwable error) {
                dispatchDataListener(listener -> listener.onFetchError(error), "onFetchError");
            }

            @Override
            public void onFetchError(LeiosPoint requestedPoint, Throwable error) {
                dispatchDataListener(listener -> listener.onFetchError(requestedPoint, error),
                        "onFetchError(point)");
            }

            @Override
            public void onDisconnect() {
                handleDisconnect();
            }
        });
    }

    void handleDisconnect() {
        leiosActive = false;
        stopKeepAliveTimer();
        leiosNotifyAgent.reset();
        leiosFetchAgent.reset();
        if (clientClosing.get()) {
            return;
        }
        if (handshakeCompleted && disconnectNotified.compareAndSet(false, true)) {
            dispatchDataListener(LeiosDataListener::onDisconnect, "onDisconnect");
        }
    }

    private void ensureActive() {
        if (!leiosActive) {
            throw new IllegalStateException("Leios mini-protocols are not active");
        }
    }

    private static VersionTable leiosVersionTable(long networkMagic) {
        N2NVersionData versionData = new N2NVersionData(networkMagic, true, 0, false);
        Map<Long, VersionData> versionTableMap = new HashMap<>();
        versionTableMap.put(N2NVersionTableConstant.PROTOCOL_V11, versionData);
        versionTableMap.put(N2NVersionTableConstant.PROTOCOL_V12, versionData);
        versionTableMap.put(N2NVersionTableConstant.PROTOCOL_V13, versionData);
        versionTableMap.put(N2NVersionTableConstant.PROTOCOL_V14, versionData);
        versionTableMap.put(N2NVersionTableConstant.PROTOCOL_V15, versionData);
        return new VersionTable(versionTableMap);
    }

    private boolean isLeiosCompatible(AcceptVersion acceptVersion) {
        if (networkMagic != Constants.MUSASHI_PROTOCOL_MAGIC
                || acceptVersion == null
                || N2NVersionTableConstant.isAppLayerVersion(acceptVersion.getVersionNumber())
                || acceptVersion.getVersionNumber() < N2NVersionTableConstant.PROTOCOL_V15) {
            return false;
        }

        VersionData versionData = acceptVersion.getVersionData();
        return versionData != null && versionData.getNetworkMagic() == Constants.MUSASHI_PROTOCOL_MAGIC;
    }

    private void startKeepAliveTimer() {
        if (!keepAliveAgent.isChannelActive() || keepAliveExecutor.isShutdown()) {
            return;
        }
        ScheduledFuture<?> current = keepAliveFuture;
        if (current != null && !current.isDone()) {
            return;
        }

        keepAliveFuture = keepAliveExecutor.scheduleAtFixedRate(() -> {
            if (!leiosActive || clientClosing.get() || !keepAliveAgent.isChannelActive()) {
                return;
            }
            try {
                keepAliveAgent.sendKeepAlive(ThreadLocalRandom.current().nextInt(KeepAliveAgent.MAX_NUM + 1));
            } catch (Exception e) {
                log.warn("Leios keep-alive send failed", e);
            }
        }, 0, KEEP_ALIVE_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void stopKeepAliveTimer() {
        ScheduledFuture<?> current = keepAliveFuture;
        if (current != null) {
            current.cancel(false);
            keepAliveFuture = null;
        }
    }

    private void waitForGracefulMiniProtocolShutdown() {
        if (!handshakeCompleted) {
            return;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(GRACEFUL_SHUTDOWN_TIMEOUT_MILLIS);
        while (System.nanoTime() < deadline) {
            if (leiosNotifyAgent.isDone() && leiosFetchAgent.isDone()) {
                return;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void dispatchDataListener(Consumer<LeiosDataListener> action, String callbackName) {
        for (LeiosDataListener listener : dataListeners) {
            try {
                action.accept(listener);
            } catch (Exception e) {
                log.warn("Leios data listener {} failed", callbackName, e);
            }
        }
    }
}
