package com.bloxbean.cardano.yaci.core.protocol.localstate;

import com.bloxbean.cardano.yaci.core.BaseTest;
import com.bloxbean.cardano.yaci.core.helpers.LocalTipFinder;
import com.bloxbean.cardano.yaci.core.network.N2CClient;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.Reason;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2CVersionTableConstant;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Query;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.QueryResult;
import com.bloxbean.cardano.yaci.core.protocol.localstate.messages.MsgFailure;
import com.bloxbean.cardano.yaci.core.protocol.localstate.queries.BlockHeightQuery;
import com.bloxbean.cardano.yaci.core.protocol.localstate.queries.ChainPointQuery;
import com.bloxbean.cardano.yaci.core.protocol.localstate.queries.SystemStartQuery;
import com.bloxbean.cardano.yaci.core.protocol.localstate.queries.SystemStartResult;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@EnabledIfEnvironmentVariable(named = "INT_TEST", matches = "true")
class LocalStateQueryAgentTest extends BaseTest {

    @Test
    void acquiredInvalidPoint_shouldReturn_MsgFailure() throws InterruptedException {
        Point acquirePoint = new Point(2348544, "75ec1008c5e8a49516c9c823bbb0363e858705ff3ae48f61794115385d0ffce8");

        HandshakeAgent handshakeAgent = new HandshakeAgent(N2CVersionTableConstant.v1AndAbove(protocolMagic));
        LocalStateQueryAgent localStateQueryAgent = new LocalStateQueryAgent(acquirePoint);

        N2CClient n2CClient = new N2CClient(nodeSocketFile, handshakeAgent, localStateQueryAgent);

        CountDownLatch countDownLatch = new CountDownLatch(1);
        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                log.info("HANDSHAKE Successful");
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
        LocalStateQueryAgent localStateQueryAgent = new LocalStateQueryAgent(knownPoint);

        N2CClient n2CClient = new N2CClient(nodeSocketFile, handshakeAgent, localStateQueryAgent);

        CountDownLatch countDownLatch = new CountDownLatch(1);
        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                log.info("HANDSHAKE Successful");
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

        Mono<Tip> tipMono = findTip();
        Tip tip = tipMono.block(Duration.ofSeconds(10));

        HandshakeAgent handshakeAgent = new HandshakeAgent(N2CVersionTableConstant.v1AndAbove(protocolMagic));
        LocalStateQueryAgent localStateQueryAgent = new LocalStateQueryAgent(tip.getPoint());

        N2CClient n2CClient = new N2CClient(nodeSocketFile, handshakeAgent, localStateQueryAgent);

        CountDownLatch countDownLatch = new CountDownLatch(2);
        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                log.info("HANDSHAKE Successful");
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

        Mono<Tip> tipMono = findTip();
        Tip tip = tipMono.block(Duration.ofSeconds(10));

        HandshakeAgent handshakeAgent = new HandshakeAgent(N2CVersionTableConstant.v1AndAbove(protocolMagic));
        LocalStateQueryAgent localStateQueryAgent = new LocalStateQueryAgent(tip.getPoint());

        N2CClient n2CClient = new N2CClient(nodeSocketFile, handshakeAgent, localStateQueryAgent);

        CountDownLatch countDownLatch = new CountDownLatch(2);
        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                log.info("HANDSHAKE Successful");
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

        Mono<Tip> tipMono = findTip();
        Tip tip = tipMono.block(Duration.ofSeconds(10));

        HandshakeAgent handshakeAgent = new HandshakeAgent(N2CVersionTableConstant.v1AndAbove(protocolMagic));
        LocalStateQueryAgent localStateQueryAgent = new LocalStateQueryAgent(tip.getPoint());

        N2CClient n2CClient = new N2CClient(nodeSocketFile, handshakeAgent, localStateQueryAgent);

        CountDownLatch countDownLatch = new CountDownLatch(2);
        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                log.info("HANDSHAKE Successful");
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

        Mono<Tip> tipMono = findTip();
        Tip tip = tipMono.block(Duration.ofSeconds(10));

        HandshakeAgent handshakeAgent = new HandshakeAgent(N2CVersionTableConstant.v1AndAbove(protocolMagic));
        LocalStateQueryAgent localStateQueryAgent = new LocalStateQueryAgent(tip.getPoint());

        N2CClient n2CClient = new N2CClient(nodeSocketFile, handshakeAgent, localStateQueryAgent);

        CountDownLatch countDownLatch = new CountDownLatch(2);
        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                log.info("HANDSHAKE Successful");
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

        localStateQueryAgent.acquire(tip.getPoint());
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

        Mono<Tip> tipMono = findTip();
        Tip tip = tipMono.block(Duration.ofSeconds(10));

        HandshakeAgent handshakeAgent = new HandshakeAgent(N2CVersionTableConstant.v1AndAbove(protocolMagic));
        LocalStateQueryAgent localStateQueryAgent = new LocalStateQueryAgent(tip.getPoint());

        N2CClient n2CClient = new N2CClient(nodeSocketFile, handshakeAgent, localStateQueryAgent);

        CountDownLatch countDownLatch = new CountDownLatch(2);
        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                log.info("HANDSHAKE Successful");
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
    public Mono<Tip> findTip() {
        return Mono.create(tipMonoSink -> {
            LocalTipFinder tipFinder = new LocalTipFinder(nodeSocketFile, Point.ORIGIN, protocolMagic);
            tipFinder.start(tip -> {
                tipMonoSink.success(tip);
                tipFinder.shutdown();
            });
        });
    }
}
