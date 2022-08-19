package com.bloxbean.cardano.yaci.core.helpers;

import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.yaci.core.network.N2NClient;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.TxSubmisionAgent;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TxSubmissionClient {
    private String host;
    private int port;
    private VersionTable versionTable;

    public TxSubmissionClient(String host, int port, VersionTable versionTable) {
        this.host = host;
        this.port = port;
        this.versionTable = versionTable;
    }

    public void start() {
        TxSubmisionAgent txSubmisionAgent =  new TxSubmisionAgent();
        N2NClient n2CClient = new N2NClient(host, port, new HandshakeAgent(N2NVersionTableConstant.v4AndAbove(Networks.mainnet().getProtocolMagic())), txSubmisionAgent);

        try {
            n2CClient.start();
        } catch (Exception e) {
           log.error("Error in main thread", e);
        }

        txSubmisionAgent.sendNextMessage();

        try {
            Thread.sleep(20000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> txSubmisionAgent.shutdown()));
    }

    public static void main(String[] args) {
        VersionTable versionTable = N2NVersionTableConstant.v4AndAbove(Networks.mainnet().getProtocolMagic());
        TxSubmissionClient blockFetcher = new TxSubmissionClient("192.168.0.228", 6000, versionTable);
        blockFetcher.start();
    }
}
