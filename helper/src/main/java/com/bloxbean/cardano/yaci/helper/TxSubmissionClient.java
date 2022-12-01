package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.network.N2NClient;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.TxSubmissionAgent;
import com.bloxbean.cardano.yaci.helper.api.Fetcher;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;

/**
 * This helper is still under development.
 * Use {@link LocalTxSubmissionClient} to submitTx to a local Cardano node.
 * Tx submission with Node to Node protocol
 */
@Slf4j
public class TxSubmissionClient implements Fetcher {
    private String host;
    private int port;
    private VersionTable versionTable;
    private HandshakeAgent handshakeAgent;
    private TxSubmissionAgent txSubmissionAgent;
    private N2NClient n2nClient;

    public TxSubmissionClient(String host, int port, VersionTable versionTable) {
        this.host = host;
        this.port = port;
        this.versionTable = versionTable;
        init();
    }

    private void init() {
        handshakeAgent = new HandshakeAgent(versionTable);
        txSubmissionAgent = new TxSubmissionAgent();
        n2nClient = new N2NClient(host, port, handshakeAgent, txSubmissionAgent);

        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                txSubmissionAgent.sendNextMessage();
            }
        });
    }

    @Override
    public void start(Consumer consumer) {
        n2nClient.start();
        txSubmissionAgent.sendNextMessage();
    }

    @Override
    public void shutdown() {
        n2nClient.shutdown();
    }

    @Override
    public boolean isRunning() {
        return n2nClient.isRunning();
    }

}
