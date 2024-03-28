package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.network.NodeServer;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.core.protocol.keepalive.KeepAliveAgent;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MainServer {

    public void start() throws InterruptedException {
        HandshakeAgent handshakeAgent = new HandshakeAgent(N2NVersionTableConstant.v4AndAbove(Constants.MAINNET_PROTOCOL_MAGIC));
        KeepAliveAgent keepAliveAgent = new KeepAliveAgent();

        NodeServer nodeServer = new NodeServer(31000, handshakeAgent, keepAliveAgent);
        nodeServer.start();

    }

    public static void main(String[] args) throws InterruptedException {
        new MainServer().start();
    }

}
