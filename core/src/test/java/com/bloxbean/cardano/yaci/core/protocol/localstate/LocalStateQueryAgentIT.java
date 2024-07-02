package com.bloxbean.cardano.yaci.core.protocol.localstate;

import com.bloxbean.cardano.yaci.core.BaseTest;
import com.bloxbean.cardano.yaci.core.model.Credential;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredType;
import com.bloxbean.cardano.yaci.core.network.UnixSocketNodeClient;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.Reason;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2CVersionTableConstant;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Query;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.QueryResult;
import com.bloxbean.cardano.yaci.core.protocol.localstate.messages.MsgFailure;
import com.bloxbean.cardano.yaci.core.protocol.localstate.queries.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@EnabledIfEnvironmentVariable(named = "INT_TEST", matches = "true")
class LocalStateQueryAgentIT extends BaseTest {

    @Test
    void acquiredInvalidPoint_shouldReturn_MsgFailure() throws InterruptedException {
        Point acquirePoint = new Point(2348544, "75ec1008c5e8a49516c9c823bbb0363e858705ff3ae48f61794115385d0ffce8");

        HandshakeAgent handshakeAgent = new HandshakeAgent(N2CVersionTableConstant.v1AndAbove(protocolMagic));
        LocalStateQueryAgent localStateQueryAgent = new LocalStateQueryAgent();

        UnixSocketNodeClient n2CClient = new UnixSocketNodeClient(nodeSocketFile, handshakeAgent, localStateQueryAgent);

        CountDownLatch countDownLatch = new CountDownLatch(1);
        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                log.info("HANDSHAKE Successful");
                localStateQueryAgent.acquire(new Point(1233, "82cd3c389e0c6c0de588db51ba6a5860bae9808a5080119033aee401c75c97d0")); //Tip
                localStateQueryAgent.sendNextMessage();
            }

            @Override
            public void handshakeError(Reason reason) {
                log.info("ERROR {}", reason);
            }
        });

        AtomicBoolean failed = new AtomicBoolean(false);
        localStateQueryAgent.addListener(new LocalStateQueryListener() {
            @Override
            public void acquireFailed(MsgFailure.Reason reason) {
                countDownLatch.countDown();
                failed.set(true);
            }

            @Override
            public void acquired(Point point) {
                countDownLatch.countDown();
            }
        });

        n2CClient.start();
        countDownLatch.await(20, TimeUnit.SECONDS);
        n2CClient.shutdown();

        assertThat(failed.get()).isTrue();
    }

    @Test
    void acquiredValidPoint_shouldReturn_Acquired() throws InterruptedException {

        HandshakeAgent handshakeAgent = new HandshakeAgent(N2CVersionTableConstant.v1AndAbove(protocolMagic));
        LocalStateQueryAgent localStateQueryAgent = new LocalStateQueryAgent();

        UnixSocketNodeClient n2CClient = new UnixSocketNodeClient(nodeSocketFile, handshakeAgent, localStateQueryAgent);

        CountDownLatch countDownLatch = new CountDownLatch(1);
        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                log.info("HANDSHAKE Successful");
                localStateQueryAgent.acquire(); //Tip
                localStateQueryAgent.sendNextMessage();
            }

            @Override
            public void handshakeError(Reason reason) {
                log.info("ERROR {}", reason);
            }
        });

        AtomicBoolean success = new AtomicBoolean(false);
        localStateQueryAgent.addListener(new LocalStateQueryListener() {
            @Override
            public void acquireFailed(MsgFailure.Reason reason) {
                countDownLatch.countDown();
            }

            @Override
            public void acquired(Point point) {
                countDownLatch.countDown();
                success.set(true);
            }
        });

        n2CClient.start();
        countDownLatch.await(20, TimeUnit.SECONDS);
        n2CClient.shutdown();

        assertThat(success.get()).isTrue();
    }

    @Test
    void systemStartQuery_shouldReturn_startDateTime() throws InterruptedException {
        HandshakeAgent handshakeAgent = new HandshakeAgent(N2CVersionTableConstant.v1AndAbove(protocolMagic));
        LocalStateQueryAgent localStateQueryAgent = new LocalStateQueryAgent();

        UnixSocketNodeClient n2CClient = new UnixSocketNodeClient(nodeSocketFile, handshakeAgent, localStateQueryAgent);

        CountDownLatch countDownLatch = new CountDownLatch(2);
        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                log.info("HANDSHAKE Successful");
                localStateQueryAgent.acquire(); //tip
                localStateQueryAgent.sendNextMessage();
            }

            @Override
            public void handshakeError(Reason reason) {
                log.info("ERROR {}", reason);
            }
        });

        AtomicBoolean failed = new AtomicBoolean(false);
        localStateQueryAgent.addListener(new LocalStateQueryListener() {
            @Override
            public void acquireFailed(MsgFailure.Reason reason) {
                countDownLatch.countDown();
                failed.set(true);
            }

            @Override
            public void acquired(Point point) {
                countDownLatch.countDown();
                localStateQueryAgent.query(new SystemStartQuery());
                localStateQueryAgent.sendNextMessage();
            }

            @Override
            public void resultReceived(Query query, QueryResult result) {
                System.out.println("Query >> " + query);
                System.out.println("Result >> " + result);
                System.out.println("Result : " + ((SystemStartResult) result).getLocalDateTime());
                countDownLatch.countDown();
            }
        });

        n2CClient.start();
        countDownLatch.await(20, TimeUnit.SECONDS);
        n2CClient.shutdown();
    }

    @Test
    void blockHeightQuery_shouldReturn_BlockHeight() throws InterruptedException {
        HandshakeAgent handshakeAgent = new HandshakeAgent(N2CVersionTableConstant.v1AndAbove(protocolMagic));
        LocalStateQueryAgent localStateQueryAgent = new LocalStateQueryAgent();

        UnixSocketNodeClient n2CClient = new UnixSocketNodeClient(nodeSocketFile, handshakeAgent, localStateQueryAgent);

        CountDownLatch countDownLatch = new CountDownLatch(2);
        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                log.info("HANDSHAKE Successful");
                localStateQueryAgent.acquire();
                localStateQueryAgent.sendNextMessage();
            }

            @Override
            public void handshakeError(Reason reason) {
                log.info("ERROR {}", reason);
            }
        });

        AtomicBoolean failed = new AtomicBoolean(false);
        localStateQueryAgent.addListener(new LocalStateQueryListener() {
            @Override
            public void acquireFailed(MsgFailure.Reason reason) {
                countDownLatch.countDown();
                failed.set(true);
            }

            @Override
            public void acquired(Point point) {
                countDownLatch.countDown();
                localStateQueryAgent.query(new BlockHeightQuery());
                localStateQueryAgent.sendNextMessage();
            }

            @Override
            public void resultReceived(Query query, QueryResult result) {
                System.out.println("Query >> " + query);
                System.out.println("Result >> " + result);
                System.out.println("Result : " + result);
                countDownLatch.countDown();
            }
        });

        n2CClient.start();
        countDownLatch.await(20, TimeUnit.SECONDS);
        n2CClient.shutdown();
    }

    @Test
    void chainPointQuery_shouldReturn_blockHash() throws InterruptedException {
        HandshakeAgent handshakeAgent = new HandshakeAgent(N2CVersionTableConstant.v1AndAbove(protocolMagic));
        LocalStateQueryAgent localStateQueryAgent = new LocalStateQueryAgent();

        UnixSocketNodeClient n2CClient = new UnixSocketNodeClient(nodeSocketFile, handshakeAgent, localStateQueryAgent);

        CountDownLatch countDownLatch = new CountDownLatch(2);
        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                log.info("HANDSHAKE Successful");
                localStateQueryAgent.acquire();
                localStateQueryAgent.sendNextMessage();
            }

            @Override
            public void handshakeError(Reason reason) {
                log.info("ERROR {}", reason);
            }
        });

        AtomicBoolean failed = new AtomicBoolean(false);
        localStateQueryAgent.addListener(new LocalStateQueryListener() {
            @Override
            public void acquireFailed(MsgFailure.Reason reason) {
                countDownLatch.countDown();
                failed.set(true);
            }

            @Override
            public void acquired(Point point) {
                countDownLatch.countDown();
                localStateQueryAgent.query(new ChainPointQuery());
                localStateQueryAgent.sendNextMessage();
            }

            @Override
            public void resultReceived(Query query, QueryResult result) {
                System.out.println("Query >> " + query);
                System.out.println("Result >> " + result);
                System.out.println("Result : " + result);
                countDownLatch.countDown();
            }
        });

        n2CClient.start();
        countDownLatch.await(20, TimeUnit.SECONDS);
        n2CClient.shutdown();
    }

    @Test
    void acquireReleaseAcquireTest() throws InterruptedException {
        HandshakeAgent handshakeAgent = new HandshakeAgent(N2CVersionTableConstant.v1AndAbove(protocolMagic));
        LocalStateQueryAgent localStateQueryAgent = new LocalStateQueryAgent();

        UnixSocketNodeClient n2CClient = new UnixSocketNodeClient(nodeSocketFile, handshakeAgent, localStateQueryAgent);

        CountDownLatch countDownLatch = new CountDownLatch(2);
        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                log.info("HANDSHAKE Successful");
                localStateQueryAgent.acquire();
                localStateQueryAgent.sendNextMessage();
            }

            @Override
            public void handshakeError(Reason reason) {
                log.info("ERROR {}", reason);
            }
        });

        localStateQueryAgent.addListener(new LocalStateQueryListener() {
            @Override
            public void resultReceived(Query query, QueryResult result) {
                System.out.println("### Query  ### " + query);
                System.out.println("### Result ### " + result);
                countDownLatch.countDown();
            }
        });

        n2CClient.start();
        Thread.sleep(500);
        localStateQueryAgent.query(new ChainPointQuery());
        localStateQueryAgent.sendNextMessage();

        Thread.sleep(50);

        localStateQueryAgent.release();
        localStateQueryAgent.sendNextMessage();

        System.out.println("RELEASE DONE >>>>>>>>>>>>>>>");
        Thread.sleep(50);

        localStateQueryAgent.acquire();
        localStateQueryAgent.sendNextMessage();

        System.out.println("ACQUIRE DONE >>>>>>>>>>>>>>>");
        Thread.sleep(50);

        System.out.println("QUERYING AGAIN >>>>>>>>>>>>>");
        localStateQueryAgent.query(new ChainPointQuery());
        localStateQueryAgent.sendNextMessage();

        countDownLatch.await(20, TimeUnit.SECONDS);
        n2CClient.shutdown();
    }

    @Test
    void invalid_invokeReleaseTwiceTest() throws InterruptedException {
        HandshakeAgent handshakeAgent = new HandshakeAgent(N2CVersionTableConstant.v1AndAbove(protocolMagic));
        LocalStateQueryAgent localStateQueryAgent = new LocalStateQueryAgent();

        UnixSocketNodeClient n2CClient = new UnixSocketNodeClient(nodeSocketFile, handshakeAgent, localStateQueryAgent);

        CountDownLatch countDownLatch = new CountDownLatch(2);
        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                log.info("HANDSHAKE Successful");
                localStateQueryAgent.acquire();
                localStateQueryAgent.sendNextMessage();
            }

            @Override
            public void handshakeError(Reason reason) {
                log.info("ERROR {}", reason);
            }
        });

        localStateQueryAgent.addListener(new LocalStateQueryListener() {
            @Override
            public void resultReceived(Query query, QueryResult result) {
                System.out.println("### Query  ### " + query);
                System.out.println("### Result ### " + result);
                countDownLatch.countDown();
            }
        });

        n2CClient.start();
        Thread.sleep(500);
        localStateQueryAgent.query(new ChainPointQuery());
        localStateQueryAgent.sendNextMessage();

        Thread.sleep(50);

        localStateQueryAgent.release();
        localStateQueryAgent.sendNextMessage();

        System.out.println("RELEASE DONE >>>>>>>>>>>>>>>");
        Thread.sleep(50);

        Assertions.assertThrows(IllegalStateException.class, () -> {
            localStateQueryAgent.release();
            localStateQueryAgent.sendNextMessage();
        });

        n2CClient.shutdown();
    }

    @Test
    void constitutionQuery_shouldReturn_Constitution() throws InterruptedException {
        HandshakeAgent handshakeAgent = new HandshakeAgent(N2CVersionTableConstant.v1AndAbove(sanchoProtocolMagic));
        LocalStateQueryAgent localStateQueryAgent = new LocalStateQueryAgent();

        UnixSocketNodeClient n2CClient = new UnixSocketNodeClient(sanchoNodeSocketFile, handshakeAgent, localStateQueryAgent);

        CountDownLatch countDownLatch = new CountDownLatch(2);
        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                log.info("HANDSHAKE Successful");
                localStateQueryAgent.acquire();
                localStateQueryAgent.sendNextMessage();
            }

            @Override
            public void handshakeError(Reason reason) {
                log.info("ERROR {}", reason);
            }
        });

        AtomicBoolean failed = new AtomicBoolean(false);
        localStateQueryAgent.addListener(new LocalStateQueryListener() {
            @Override
            public void acquireFailed(MsgFailure.Reason reason) {
                countDownLatch.countDown();
                failed.set(true);
            }

            @Override
            public void acquired(Point point) {
                countDownLatch.countDown();
                localStateQueryAgent.query(new ConstitutionQuery(Era.Conway));
                localStateQueryAgent.sendNextMessage();
            }

            @Override
            public void resultReceived(Query query, QueryResult result) {
                System.out.println("Query >> " + query);
                System.out.println("Result >> " + result);
                System.out.println("Result : " + result);
                countDownLatch.countDown();
            }
        });

        n2CClient.start();
        countDownLatch.await(20, TimeUnit.SECONDS);
        n2CClient.shutdown();
    }

    @Test
    void dRepStateQuery_shouldReturn_dRepStateList() throws InterruptedException {
        HandshakeAgent handshakeAgent = new HandshakeAgent(N2CVersionTableConstant.v1AndAbove(sanchoProtocolMagic));
        LocalStateQueryAgent localStateQueryAgent = new LocalStateQueryAgent();

        UnixSocketNodeClient n2CClient = new UnixSocketNodeClient(sanchoNodeSocketFile, handshakeAgent, localStateQueryAgent);

        CountDownLatch countDownLatch = new CountDownLatch(2);
        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                log.info("HANDSHAKE Successful");
                localStateQueryAgent.acquire();
                localStateQueryAgent.sendNextMessage();
            }

            @Override
            public void handshakeError(Reason reason) {
                log.info("ERROR {}", reason);
            }
        });

        AtomicBoolean failed = new AtomicBoolean(false);
        localStateQueryAgent.addListener(new LocalStateQueryListener() {
            @Override
            public void acquireFailed(MsgFailure.Reason reason) {
                countDownLatch.countDown();
                failed.set(true);
            }

            @Override
            public void acquired(Point point) {
                countDownLatch.countDown();
                localStateQueryAgent.query(new DRepStateQuery(List.of(Credential
                        .builder()
                        .type(StakeCredType.ADDR_KEYHASH)
                        .hash("11b2324445c59782060c4201838c6390bb9d8220cf46538e4f2a73ea").build())));
                localStateQueryAgent.sendNextMessage();
            }

            @Override
            public void resultReceived(Query query, QueryResult result) {
                System.out.println("Query >> " + query);
                System.out.println("Result >> " + result);
                countDownLatch.countDown();
            }
        });

        n2CClient.start();
        countDownLatch.await(20, TimeUnit.SECONDS);
        n2CClient.shutdown();
    }

}
