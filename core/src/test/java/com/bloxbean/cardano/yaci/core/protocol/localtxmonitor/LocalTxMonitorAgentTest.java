package com.bloxbean.cardano.yaci.core.protocol.localtxmonitor;

import com.bloxbean.cardano.yaci.core.BaseTest;
import com.bloxbean.cardano.yaci.core.network.N2CClient;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.Reason;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2CVersionTableConstant;
import com.bloxbean.cardano.yaci.core.protocol.localtxmonitor.messages.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@EnabledIfEnvironmentVariable(named = "INT_TEST", matches = "true")
public class LocalTxMonitorAgentTest extends BaseTest {

    @Test
    void whenAwaitAcquire_thenReturnAcquired() throws InterruptedException {

        HandshakeAgent handshakeAgent = new HandshakeAgent(N2CVersionTableConstant.v1AndAbove(protocolMagic));
        LocalTxMonitorAgent localTxMonitorAgent = new LocalTxMonitorAgent();

        N2CClient n2CClient = new N2CClient(nodeSocketFile, handshakeAgent, localTxMonitorAgent);

        CountDownLatch countDownLatch = new CountDownLatch(1);
        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                log.info("HANDSHAKE Successful");
                localTxMonitorAgent.sendNextMessage();
            }

            @Override
            public void handshakeError(Reason reason) {
                log.info("ERROR {}", reason);
            }
        });

        AtomicBoolean success = new AtomicBoolean(false);
        AtomicLong atomicSlot = new AtomicLong(0);
        localTxMonitorAgent.addListener(new LocalTxMonitorListener() {
            @Override
            public void acquiredAt(long slot) {
                log.info("Slot >> " + slot);
                atomicSlot.set(slot);
                countDownLatch.countDown();
                success.set(true);
            }
        });

        n2CClient.start();
        countDownLatch.await(20, TimeUnit.SECONDS);
        n2CClient.shutdown();

        assertThat(success.get()).isTrue();
        assertThat(atomicSlot.get()).isGreaterThan(1000);
    }

    @Test
    void whenRelease_thenReturnToIdle() throws InterruptedException {

        HandshakeAgent handshakeAgent = new HandshakeAgent(N2CVersionTableConstant.v1AndAbove(protocolMagic));
        LocalTxMonitorAgent localTxMonitorAgent = new LocalTxMonitorAgent();

        N2CClient n2CClient = new N2CClient(nodeSocketFile, handshakeAgent, localTxMonitorAgent);

        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                log.info("HANDSHAKE Successful");
                localTxMonitorAgent.sendNextMessage();
            }

            @Override
            public void handshakeError(Reason reason) {
                log.info("ERROR {}", reason);
            }
        });

        CountDownLatch countDownLatch = new CountDownLatch(2);
        AtomicInteger counter = new AtomicInteger(0);
        localTxMonitorAgent.addListener(new LocalTxMonitorListener() {
            @Override
            public void acquiredAt(long slot) {
                countDownLatch.countDown();
                counter.incrementAndGet();
                log.info("Slot >> " + slot);
                localTxMonitorAgent.release();
                localTxMonitorAgent.sendNextMessage();

                localTxMonitorAgent.awaitAcquire(); //TODO --
                localTxMonitorAgent.sendNextMessage();
            }

        });

        n2CClient.start();
        countDownLatch.await(20, TimeUnit.SECONDS);
        n2CClient.shutdown();

        assertThat(counter.get()).isEqualTo(2);
    }

    @Test
    void whenNextTx_thenReturnNextTx() throws InterruptedException {

        HandshakeAgent handshakeAgent = new HandshakeAgent(N2CVersionTableConstant.v1AndAbove(protocolMagic));
        LocalTxMonitorAgent localTxMonitorAgent = new LocalTxMonitorAgent();

        N2CClient n2CClient = new N2CClient(nodeSocketFile, handshakeAgent, localTxMonitorAgent);

        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                log.info("HANDSHAKE Successful");
                localTxMonitorAgent.sendNextMessage();
            }

            @Override
            public void handshakeError(Reason reason) {
                log.info("ERROR {}", reason);
            }
        });

        AtomicBoolean success = new AtomicBoolean(false);
        CountDownLatch onNextCallbackCDL = new CountDownLatch(1);
        localTxMonitorAgent.addListener(new LocalTxMonitorListener() {
            @Override
            public void acquiredAt(long slot) {
                log.info("Slot >> " + slot);
                localTxMonitorAgent.nextTx();
                localTxMonitorAgent.sendNextMessage();
            }

            @Override
            public void onReplyNextTx(MsgNextTx request, MsgReplyNextTx reply) {
                success.set(true);
                onNextCallbackCDL.countDown();
            }
        });

        n2CClient.start();
        onNextCallbackCDL.await(20, TimeUnit.SECONDS);
        n2CClient.shutdown();

        assertThat(success.get()).isTrue();
    }

    //TODO -- HasTx not working. Need to fix
  //  @Test
    void whenHasTx_thenReturnFalse() throws InterruptedException {

        HandshakeAgent handshakeAgent = new HandshakeAgent(N2CVersionTableConstant.v1AndAbove(protocolMagic));
        LocalTxMonitorAgent localTxMonitorAgent = new LocalTxMonitorAgent();

        N2CClient n2CClient = new N2CClient(nodeSocketFile, handshakeAgent, localTxMonitorAgent);
        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                log.info("HANDSHAKE Successful");
                localTxMonitorAgent.sendNextMessage();
            }

            @Override
            public void handshakeError(Reason reason) {
                log.info("ERROR {}", reason);
            }
        });

        AtomicBoolean success = new AtomicBoolean(false);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        localTxMonitorAgent.addListener(new LocalTxMonitorListener() {
            @Override
            public void acquiredAt(long slot) {
                log.info("Slot >> " + slot);
                localTxMonitorAgent.hasTx("4f539156bfbefc070a3b61cad3d1cedab3050e2b2a62f0ffe16a43eb0edc1ce8");
                localTxMonitorAgent.sendNextMessage();
            }

            @Override
            public void onReplyHashTx(MsgHasTx request, MsgReplyHasTx reply) {
                log.info("Found tx: " + reply.hasTx());
                success.set(true);
                countDownLatch.countDown();
            }
        });

        n2CClient.start();
        countDownLatch.await(20, TimeUnit.SECONDS);
        n2CClient.shutdown();

        assertThat(success.get()).isTrue();
    }

    @Test
    void whenGetSizes_thenReturnSizes() throws InterruptedException {

        HandshakeAgent handshakeAgent = new HandshakeAgent(N2CVersionTableConstant.v1AndAbove(protocolMagic));
        LocalTxMonitorAgent localTxMonitorAgent = new LocalTxMonitorAgent();

        N2CClient n2CClient = new N2CClient(nodeSocketFile, handshakeAgent, localTxMonitorAgent);
        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                log.info("HANDSHAKE Successful");
                localTxMonitorAgent.sendNextMessage();
            }

            @Override
            public void handshakeError(Reason reason) {
                log.info("ERROR {}", reason);
            }
        });

        AtomicInteger capacity = new AtomicInteger(0);
        AtomicInteger size = new AtomicInteger(-1);
        AtomicInteger noTx = new AtomicInteger(-1);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        localTxMonitorAgent.addListener(new LocalTxMonitorListener() {
            @Override
            public void acquiredAt(long slot) {
                log.info("Slot >> " + slot);
                localTxMonitorAgent.getSizeAndCapacity();
//                localTxMonitorAgent.nextTx();
                localTxMonitorAgent.sendNextMessage();
            }

            @Override
            public void onReplyGetSizes(MsgGetSizes request, MsgReplyGetSizes reply) {
                capacity.set(reply.getCapacityInBytes());
                size.set(reply.getSizeInBytes());
                noTx.set(reply.getNumberOfTxs());
                countDownLatch.countDown();
            }
        });

        n2CClient.start();
        countDownLatch.await(20, TimeUnit.SECONDS);
        n2CClient.shutdown();

        assertThat(capacity.get()).isGreaterThan(1000);
        assertThat(size.get()).isGreaterThanOrEqualTo(0);
        assertThat(noTx.get()).isGreaterThanOrEqualTo(0);
    }
}
