package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.network.NodeServer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainsyncAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.core.protocol.keepalive.KeepAliveAgent;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.TxSubmissionAgent;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MainServer {

    public void start() throws InterruptedException {
        HandshakeAgent handshakeAgent = new HandshakeAgent(N2NVersionTableConstant.v4AndAbove(Constants.MAINNET_PROTOCOL_MAGIC), false);
        KeepAliveAgent keepAliveAgent = new KeepAliveAgent(false);
        TxSubmissionAgent txSubmissionAgent = new TxSubmissionAgent(false);
        var point = Constants.WELL_KNOWN_MAINNET_POINT;
        ChainsyncAgent chainsyncAgent = new ChainsyncAgent(new Point[]{point}, false);

        NodeServer nodeServer = new NodeServer(31000, handshakeAgent, keepAliveAgent, txSubmissionAgent, chainsyncAgent);
        nodeServer.start();

    }

    public static void main(String[] args) throws InterruptedException {
        new MainServer().start();
    }

}
