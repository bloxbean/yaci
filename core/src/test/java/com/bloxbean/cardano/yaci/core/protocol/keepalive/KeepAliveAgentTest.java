package com.bloxbean.cardano.yaci.core.protocol.keepalive;

import com.bloxbean.cardano.yaci.core.BaseTest;
import com.bloxbean.cardano.yaci.core.network.TCPNodeClient;
import com.bloxbean.cardano.yaci.core.network.UnixSocketNodeClient;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.Reason;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2CVersionTableConstant;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@EnabledIfEnvironmentVariable(named = "INT_TEST", matches = "true")
class KeepAliveAgentTest extends BaseTest {

    //@Test
    void keepAliveN2C() throws InterruptedException {
        HandshakeAgent handshakeAgent = new HandshakeAgent(N2CVersionTableConstant.v1AndAbove(protocolMagic));
        KeepAliveAgent keepAliveAgent = new KeepAliveAgent();

        UnixSocketNodeClient n2CClient = new UnixSocketNodeClient(nodeSocketFile, handshakeAgent, keepAliveAgent);

        CountDownLatch countDownLatch = new CountDownLatch(2);
        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                log.info("HANDSHAKE Successful");
                keepAliveAgent.sendNextMessage();
                countDownLatch.countDown();
            }

            @Override
            public void handshakeError(Reason reason) {
                log.info("ERROR {}", reason);
            }
        });

        keepAliveAgent.addListener(response -> {
            log.info("Keep Alive respone >> " + response);
            countDownLatch.countDown();
        });
        n2CClient.start();

        countDownLatch.await();
      //  countDownLatch.await(10, TimeUnit.SECONDS);
    }

    @Test
    void keepAliveN2N() throws InterruptedException {
        HandshakeAgent handshakeAgent = new HandshakeAgent(N2NVersionTableConstant.v4AndAbove(protocolMagic));
        KeepAliveAgent keepAliveAgent = new KeepAliveAgent();

        TCPNodeClient n2nClient = new TCPNodeClient(node, nodePort, handshakeAgent, keepAliveAgent);

        AtomicInteger keepAliveResp = new AtomicInteger();
        CountDownLatch countDownLatch = new CountDownLatch(2);
        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                log.info("HANDSHAKE Successful");
                keepAliveAgent.sendKeepAlive(56701);
                countDownLatch.countDown();
            }

            @Override
            public void handshakeError(Reason reason) {
                log.info("ERROR {}", reason);
            }
        });

        keepAliveAgent.addListener(response -> {
            log.info("Keep Alive respone >> " + response);
            keepAliveResp.set(response.getCookie());
            countDownLatch.countDown();
        });
        n2nClient.start();

        countDownLatch.await(10, TimeUnit.SECONDS);
        assertThat(keepAliveResp.get()).isEqualTo(56701);
    }

}
