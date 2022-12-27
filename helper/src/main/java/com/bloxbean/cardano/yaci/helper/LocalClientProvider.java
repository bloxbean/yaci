package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.network.NodeClient;
import com.bloxbean.cardano.yaci.core.network.TCPNodeClient;
import com.bloxbean.cardano.yaci.core.network.UnixSocketNodeClient;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2CVersionTableConstant;
import com.bloxbean.cardano.yaci.core.protocol.localstate.LocalStateQueryAgent;
import com.bloxbean.cardano.yaci.core.protocol.localstate.LocalStateQueryListener;
import com.bloxbean.cardano.yaci.core.protocol.localtx.LocalTxSubmissionAgent;
import com.bloxbean.cardano.yaci.core.protocol.localtx.LocalTxSubmissionListener;
import com.bloxbean.cardano.yaci.core.protocol.localtxmonitor.LocalTxMonitorAgent;
import com.bloxbean.cardano.yaci.core.protocol.localtxmonitor.LocalTxMonitorListener;
import lombok.extern.slf4j.Slf4j;

/**
 * Use this to query local ledger state, monitor mempool transactions or submit transaction using Node-to-client
 * protocols. The actual operation is handled by the specific protocol client, but this class handles the common
 * tasks like connection setup, shutdown etc.
 * <p>
 * This class initializes following agents<br>
 * - {@link LocalStateQueryAgent} <br>
 * - {@link LocalTxMonitorAgent} <br>
 * - {@link LocalTxMonitorAgent} <br>
 * <p>
 * Example: Query system start time
 * </p>
 * <pre>
 * {@code
 * LocalClientProvider localClientProvider = new LocalClientProvider(nodeSocketFile, Constants.PREVIEW_PROTOCOL_MAGIC);
 * localClientProvider.start();
 *
 * LocalStateQueryClient queryClient = localClientProvider.getLocalStateQueryClient();
 *
 * Mono<SystemStartResult> queryResultMono = queryClient.executeQuery(new SystemStartQuery());
 * SystemStartResult result = queryResultMono.block();
 * }
 * </pre>
 */
@Slf4j
public class LocalClientProvider {
    private String nodeSocketFile;
    private String host;
    private int port;
    private VersionTable versionTable;
    private HandshakeAgent handshakeAgent;
    private LocalStateQueryAgent localStateQueryAgent;
    private LocalTxMonitorAgent localTxMonitorAgent;
    private LocalTxSubmissionAgent localTxSubmissionAgent;
    private NodeClient n2cClient;

    private LocalStateQueryClient localStateQueryClient;
    private LocalTxMonitorClient localTxMonitorClient;
    private LocalTxSubmissionClient localTxSubmissionClient;

    /**
     * Construct a LocalStateQueryClient
     *
     * @param nodeSocketFile Cardano node socket file
     * @param protocolMagic  Protocol Magic
     */
    public LocalClientProvider(String nodeSocketFile, long protocolMagic) {
        this(nodeSocketFile, N2CVersionTableConstant.v1AndAbove(protocolMagic));
    }

    /**
     * Constuct a LocalStateQueryClient
     *
     * @param nodeSocketFile Cardano node socket file
     * @param versionTable   VersionTable for Node-to-Client protocol
     */
    public LocalClientProvider(String nodeSocketFile, VersionTable versionTable) {
        this.nodeSocketFile = nodeSocketFile;
        this.versionTable = versionTable;
        init();
    }

    /**
     * Construct a LocalStateQueryClient using host and port (node to client protocol)
     * To expose n2c protocol through TCP, a relay tool like socat can be used.
     *
     * @param host          address to Cardano node node-to-client via tcp
     * @param port          port to Cardano node node-to-client via tcp
     * @param protocolMagic protocol magic
     */
    public LocalClientProvider(String host, int port, long protocolMagic) {
        this(host, port, N2CVersionTableConstant.v1AndAbove(protocolMagic));
    }

    /**
     * Construct a LocalStateQueryClient using host and port (node to client protocol)
     * To expose n2c protocol through TCP, a relay tool like socat can be used.
     *
     * @param host         address to Cardano node node-to-client via tcp
     * @param port         port to Cardano node node-to-client via tcp
     * @param versionTable versionTable
     */
    public LocalClientProvider(String host, int port, VersionTable versionTable) {
        this.host = host;
        this.port = port;
        this.versionTable = versionTable;
        init();
    }

    private void init() {
        handshakeAgent = new HandshakeAgent(versionTable);
        localStateQueryAgent = new LocalStateQueryAgent();
        localTxMonitorAgent = new LocalTxMonitorAgent();
        localTxSubmissionAgent = new LocalTxSubmissionAgent();

        if (nodeSocketFile != null && !nodeSocketFile.isEmpty()) {
            n2cClient = new UnixSocketNodeClient(nodeSocketFile, handshakeAgent, localStateQueryAgent,
                    localTxMonitorAgent, localTxSubmissionAgent);
        } else if (host != null && !host.isEmpty())
            n2cClient = new TCPNodeClient(host, port, handshakeAgent, localStateQueryAgent,
                    localTxMonitorAgent, localTxSubmissionAgent);

        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                localStateQueryAgent.sendNextMessage();
                //await acquire to keep the connection live
                localTxMonitorAgent.awaitAcquire();
                localTxMonitorAgent.sendNextMessage();
                localTxSubmissionAgent.sendNextMessage();
            }
        });

        localStateQueryClient = new LocalStateQueryClient(localStateQueryAgent);
        localTxMonitorClient = new LocalTxMonitorClient(localTxMonitorAgent);
        localTxSubmissionClient = new LocalTxSubmissionClient(localTxSubmissionAgent);
    }

    /**
     * Establish the connection with the local Cardano node
     * This method should be called first
     */
    public void start() {
        n2cClient.start();
    }

    public LocalStateQueryClient getLocalStateQueryClient() {
        return localStateQueryClient;
    }

    public LocalTxMonitorClient getTxMonitorClient() {
        return localTxMonitorClient;
    }

    public LocalTxSubmissionClient getTxSubmissionClient() {
        return localTxSubmissionClient;
    }

    /**
     * Invoke this method to shutdown the connection
     */
    public void shutdown() {
        n2cClient.shutdown();
    }

    /**
     * Check if the connection is alive
     *
     * @return
     */
    public boolean isRunning() {
        return n2cClient.isRunning();
    }

    /**
     * Add a {@link LocalStateQueryListener} to listen query events published by {@link LocalStateQueryAgent}
     *
     * @param listener
     */
    public void addLocalStateQueryListener(LocalStateQueryListener listener) {
        if (this.isRunning())
            throw new IllegalStateException("Listener can be added only before start() call");

        if (listener != null)
            localStateQueryAgent.addListener(listener);
    }

    /**
     * Add a {@link LocalTxMonitorClient} to listen query events published by {@link LocalTxMonitorAgent}
     *
     * @param listener
     */
    public void addLocalTxMonitorListener(LocalTxMonitorListener listener) {
        if (this.isRunning())
            throw new IllegalStateException("Listener can be added only before start() call");

        if (listener != null)
            localTxMonitorAgent.addListener(listener);
    }

    /**
     * Add a {@link LocalTxSubmissionListener} to listen {@link LocalTxSubmissionAgent} events
     *
     * @param listener
     */
    public void addTxSubmissionListener(LocalTxSubmissionListener listener) {
        if (this.isRunning())
            throw new IllegalStateException("Listener can be added only before start() call");

        if (listener != null)
            localTxSubmissionAgent.addListener(listener);
    }
}
