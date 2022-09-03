package com.bloxbean.cardano.yaci.core.helpers;

import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.yaci.core.helpers.api.Fetcher;
import com.bloxbean.cardano.yaci.core.network.N2NClient;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.TxSubmissionAgent;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;

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


    public static void main(String[] args) {
        VersionTable versionTable = N2NVersionTableConstant.v4AndAbove(Networks.mainnet().getProtocolMagic());
        TxSubmissionClient blockFetcher = new TxSubmissionClient("192.168.0.228", 6000, versionTable);
        blockFetcher.start((t) -> {});
    }
}
