package com.bloxbean.cardano.yaci.core.network.server;

import com.bloxbean.cardano.yaci.core.network.TCPNodeClient;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.Reason;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
public class NodeServerTest {

    @Test
    void startNodeServer_successfulHandshake() throws InterruptedException {
        NodeServer server = new NodeServer(3333, N2NVersionTableConstant.v11AndAbove(1));
        Thread t = new Thread() {
            @Override
            public void run() {
                server.start();
            }
        };

        t.start();

        HandshakeAgent handshakeAgent = new HandshakeAgent(N2NVersionTableConstant.v11AndAbove(1), true);
        TCPNodeClient tcpNodeClient = new TCPNodeClient("localhost", 3333, handshakeAgent);

        AtomicBoolean success = new AtomicBoolean();
        CountDownLatch countDownLatch = new CountDownLatch(1);
        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                log.info("HANDSHAKE Successful");
                countDownLatch.countDown();
                success.set(true);
            }

            @Override
            public void handshakeError(Reason reason) {
                log.info("ERROR {}", reason);
            }
        });

        tcpNodeClient.start();

        countDownLatch.await(10, TimeUnit.SECONDS);
        t.stop();
        assertThat(success).isTrue();
    }
}
