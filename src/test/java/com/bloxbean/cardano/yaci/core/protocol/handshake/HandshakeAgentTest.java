package com.bloxbean.cardano.yaci.core.protocol.handshake;

import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.yaci.core.network.Disposable;
import com.bloxbean.cardano.yaci.core.network.N2NClient;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.Reason;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

@Slf4j
class HandshakeAgentTest extends BaseTest{

    @Test
    @Timeout(value = 10000, unit = TimeUnit.MILLISECONDS)
    public void testHandshake() throws InterruptedException {
        N2NClient n2CClient = null;
        n2CClient = new N2NClient(node, nodePort);

        HandshakeAgent handshakeAgent = new HandshakeAgent(N2NVersionTableConstant.v4AndAbove(Networks.testnet().getProtocolMagic()));
        BlockfetchAgent blockfetchAgent = new BlockfetchAgent(knownPoint, knownPoint);

        Disposable disposable = n2CClient.start(handshakeAgent, blockfetchAgent);

        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                log.info("HANDSHAKE Successful");
            }

            @Override
            public void handshakeError(Reason reason) {
                log.info("ERROR {}", reason);
            }
        });

        Thread.sleep(500);
        disposable.dispose();

    }

}
