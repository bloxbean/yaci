package com.bloxbean.cardano.yaci.core.protocol.handshake;

import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.network.N2CClient;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2c.LocalChainSyncAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.Reason;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2CVersionTableConstant;
import com.bloxbean.cardano.yaci.core.protocol.localtx.LocalTxSubmissionAgent;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Slf4j
@Disabled
class HandshakeAgentTest extends BaseTest{

//    @Test
//    @Timeout(value = 10000, unit = TimeUnit.MILLISECONDS)
//    public void testHandshake() throws InterruptedException {
//
//        HandshakeAgent handshakeAgent = new HandshakeAgent(N2NVersionTableConstant.v4AndAbove(Networks.testnet().getProtocolMagic()));
//        BlockfetchAgent blockfetchAgent = new BlockfetchAgent(knownPoint, knownPoint);
//
//        N2NClient n2CClient = new N2NClient(node, nodePort, handshakeAgent, blockfetchAgent);
//        n2CClient.start();
//
//        handshakeAgent.addListener(new HandshakeAgentListener() {
//            @Override
//            public void handshakeOk() {
//                log.info("HANDSHAKE Successful");
//            }
//
//            @Override
//            public void handshakeError(Reason reason) {
//                log.info("ERROR {}", reason);
//            }
//        });
//
//        Thread.sleep(500);
//        n2CClient.shutdown();
//
//    }

    @Test
    public void testHandshake() throws InterruptedException {

        HandshakeAgent handshakeAgent = new HandshakeAgent(N2CVersionTableConstant.v1AndAbove(1));
//        HandshakeAgent handshakeAgent = new HandshakeAgent(N2NVersionTableConstant.v4AndAbove(1));
        String nodeSocketFile = "/Users/satya/work/cardano-node/prepod/db/node.socket";


        LocalTxSubmissionAgent localTxSubmissionAgent = new LocalTxSubmissionAgent();
        LocalChainSyncAgent localChainSyncAgent = new LocalChainSyncAgent(new Point[]{Constants.WELL_KNOWN_PREPOD_POINT});
//        TxSubmissionAgent txSubmisionAgent = new TxSubmissionAgent();
        N2CClient n2CClient = new N2CClient(nodeSocketFile, handshakeAgent, localChainSyncAgent);
//        N2NClient n2CClient = new N2NClient("localhost", 30000, handshakeAgent, localTxSubmissionAgent);
        n2CClient.start();

        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                log.info("HANDSHAKE Successful");
                localChainSyncAgent.sendNextMessage();
            }

            @Override
            public void handshakeError(Reason reason) {
                log.info("ERROR {}", reason);
            }
        });

        while (true)
            Thread.sleep(500);
//
//        n2CClient.shutdown();

    }

}
