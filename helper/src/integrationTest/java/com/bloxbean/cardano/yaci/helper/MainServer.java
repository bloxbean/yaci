package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.yaci.core.network.NodeServer;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.core.protocol.keepalive.KeepAliveAgent;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.TxSubmissionAgent;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MainServer {

    public void start() throws InterruptedException {
        YaciConfig.INSTANCE.setServer(true);
        HandshakeAgent handshakeAgent = new HandshakeAgent(N2NVersionTableConstant.v4AndAbove(Constants.MAINNET_PROTOCOL_MAGIC));
        KeepAliveAgent keepAliveAgent = new KeepAliveAgent();
        var txSubmissionAgent = new TxSubmissionAgent();
        var blockfetchAgent = new BlockfetchAgent();

        NodeServer nodeServer = new NodeServer(31000, handshakeAgent, keepAliveAgent, txSubmissionAgent, blockfetchAgent);
        nodeServer.start();

    }

    public static void main(String[] args) throws InterruptedException {
        new MainServer().start();
    }

}
