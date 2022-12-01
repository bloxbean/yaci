package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.network.N2CClient;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2CVersionTableConstant;
import com.bloxbean.cardano.yaci.core.protocol.localstate.LocalStateQueryAgent;
import com.bloxbean.cardano.yaci.core.protocol.localstate.LocalStateQueryListener;
import com.bloxbean.cardano.yaci.core.protocol.localtxmonitor.LocalTxMonitorAgent;
import com.bloxbean.cardano.yaci.core.protocol.localtxmonitor.LocalTxMonitorListener;
import lombok.extern.slf4j.Slf4j;

/**
 * Use this to query local ledger state using Node-to-client local-state-query mini-protocol
 *
 * <p>
 * Example: Query system start time
 * </p>
 * <pre>
 * {@code
 * LocalStateQueryClient queryClient = new LocalStateQueryClient(nodeSocketFile, Constants.PREVIEW_PROTOCOL_MAGIC);
 * queryClient.start();
 *
 * Mono<SystemStartResult> queryResultMono = queryClient.executeQuery(new SystemStartQuery());
 * SystemStartResult result = queryResultMono.block();
 * }
 * </pre>
 */
@Slf4j
public class LocalQueryProvider {
    private String nodeSocketFile;
    private VersionTable versionTable;
    private HandshakeAgent handshakeAgent;
    private LocalStateQueryAgent localStateQueryAgent;
    private LocalTxMonitorAgent localTxMonitorAgent;
    private N2CClient n2cClient;

    private LocalStateQueryClient localStateQueryClient;
    private LocalTxMonitorClient localTxMonitorClient;

    /**
     * Construct a LocalStateQueryClient
     *
     * @param nodeSocketFile Cardano node socket file
     * @param protocolMagic  Protocol Magic
     */
    public LocalQueryProvider(String nodeSocketFile, long protocolMagic) {
        this(nodeSocketFile, N2CVersionTableConstant.v1AndAbove(protocolMagic));
    }

    /**
     * Constuct a LocalStateQueryClient
     *
     * @param nodeSocketFile Cardano node socket file
     * @param versionTable   VersionTable for Node-to-Client protocol
     */
    public LocalQueryProvider(String nodeSocketFile, VersionTable versionTable) {
        this.nodeSocketFile = nodeSocketFile;
        this.versionTable = versionTable;
        init();
    }

    private void init() {
        handshakeAgent = new HandshakeAgent(versionTable);
        localStateQueryAgent = new LocalStateQueryAgent();
        localTxMonitorAgent = new LocalTxMonitorAgent();

        n2cClient = new N2CClient(nodeSocketFile, handshakeAgent, localStateQueryAgent, localTxMonitorAgent);

        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                localStateQueryAgent.sendNextMessage();
                localTxMonitorAgent.sendNextMessage();
            }
        });

        localStateQueryClient = new LocalStateQueryClient(localStateQueryAgent);
        localTxMonitorClient = new LocalTxMonitorClient(localTxMonitorAgent);
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

}
