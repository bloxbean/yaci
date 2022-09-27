package com.bloxbean.cardano.yaci.core.protocol.handshake;

import com.bloxbean.cardano.yaci.core.BaseTest;
import com.bloxbean.cardano.yaci.core.common.TxBodyType;
import com.bloxbean.cardano.yaci.core.network.N2CClient;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.Reason;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2CVersionTableConstant;
import com.bloxbean.cardano.yaci.core.protocol.localtx.LocalTxSubmissionAgent;
import com.bloxbean.cardano.yaci.core.protocol.localtx.LocalTxSubmissionListener;
import com.bloxbean.cardano.yaci.core.protocol.localtx.messages.MsgAcceptTx;
import com.bloxbean.cardano.yaci.core.protocol.localtx.messages.MsgRejectTx;
import com.bloxbean.cardano.yaci.core.protocol.localtx.model.TxSubmissionRequest;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@Slf4j
@EnabledIfEnvironmentVariable(named = "INT_TEST", matches = "true")
class HandshakeAgentTest extends BaseTest {

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

        HandshakeAgent handshakeAgent = new HandshakeAgent(N2CVersionTableConstant.v1AndAbove(2));
        String nodeSocketFile = "/Users/satya/work/cardano-node/preview/db/node.socket";

        LocalTxSubmissionAgent localTxSubmissionAgent = new LocalTxSubmissionAgent();
        localTxSubmissionAgent.addListener(new LocalTxSubmissionListener() {
            @Override
            public void txAccepted(TxSubmissionRequest request, MsgAcceptTx msgAcceptTx) {
                System.out.println("REQUEST >> " + request);
                System.out.println("ACCEPTED >> " + msgAcceptTx);
            }

            @Override
            public void txRejected(TxSubmissionRequest request, MsgRejectTx msgRejectTx) {
                System.out.println("REQUEST >> " + request.toString());
                System.out.println("REJECTED >> " + msgRejectTx);
            }
        });

        N2CClient n2CClient = new N2CClient(nodeSocketFile, handshakeAgent, localTxSubmissionAgent);
        n2CClient.start();

        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                log.info("HANDSHAKE Successful");
                byte[] txBytes = HexUtil.decodeHexString("84a30081825820b0753563f3fb5275647cacce8c9d24b9a58a65e3a4d5b3fc8c9fb68230eb4f1f010182825839001c1ffaf141ebbb8e3a7072bb15f50f938b994c82de2d175f358fc942441f00edfe1b8d6a84f0d19c25a9c8829442160c0b5c758094c423441a002dc6c0825839008c5bf0f2af6f1ef08bb3f6ec702dd16e1c514b7e1d12f7549b47db9f4d943c7af0aaec774757d4745d1a2c8dd3220e6ec2c9df23f757a2f81b0000000253ba77d6021a00029075a100818258209518c18103cbdab9c6e60b58ecc3e2eb439fef6519bb22570f391327381900a8584028ccef8e37ba03254556727ffa6969628feee642ab3cd58ec223be91c9cb19f9bfc7a6fe1c281ace676320a7cc8803fd4c0e51e09ec2312397fe01c23f424803f5f6");
                TxSubmissionRequest txnRequest = new TxSubmissionRequest(TxBodyType.BABBAGE, txBytes);
                localTxSubmissionAgent.submitTx(txnRequest);
                localTxSubmissionAgent.sendNextMessage();
            }

            @Override
            public void handshakeError(Reason reason) {
                log.info("ERROR {}", reason);
            }
        });

        Thread.sleep(10000);
    }

}
