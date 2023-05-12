package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.protocol.localtxmonitor.LocalTxMonitorListener;
import com.bloxbean.cardano.yaci.core.protocol.localtxmonitor.messages.MsgAcquired;
import com.bloxbean.cardano.yaci.core.protocol.localtxmonitor.messages.MsgAwaitAcquire;
import com.bloxbean.cardano.yaci.helper.model.MempoolStatus;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
class LocalTxMonitorClientIT extends BaseTest {
    private LocalClientProvider localQueryProvider;
    private LocalTxMonitorClient localTxMonitorClient;

    @BeforeEach
    public void setup() {
        this.localQueryProvider = new LocalClientProvider(nodeSocketFile, protocolMagic);
        this.localTxMonitorClient = localQueryProvider.getTxMonitorClient();
        localQueryProvider.start();
    }

    @AfterEach
    public void tearDown() {
        this.localQueryProvider.shutdown();
    }

    @Test
    void whenAcquire_returnSlotNo() {
        localTxMonitorClient.acquire();
        Mono<Long> mono = localTxMonitorClient.acquire();
        StepVerifier
                .create(mono)
                .expectNextMatches(slot -> slot > 1000)
                .expectComplete()
                .verify();
    }

    @Test
    void whenGetMempoolSize_returnSizeAndCapacity() {
        localTxMonitorClient.acquire().block(Duration.ofSeconds(10));
        Mono<MempoolStatus> mono = localTxMonitorClient.getMempoolSizeAndCapacity();

        StepVerifier.create(mono)
                .expectNextMatches(mpStatus -> mpStatus.getCapacityInBytes() > 1000
                        && mpStatus.getNumberOfTxs() >= 0
                        && mpStatus.getSizeInBytes() >= 0
                )
                .expectComplete()
                .verify();
    }

    @Test
    void whenAcquireAndGetMempoolSizeAndCapacity_returnSizeAndCapacity() {
        Mono<MempoolStatus> mono = localTxMonitorClient.acquireAndGetMempoolSizeAndCapacity();

        StepVerifier.create(mono)
                .expectNextMatches(mpStatus -> mpStatus.getCapacityInBytes() > 1000
                        && mpStatus.getNumberOfTxs() >= 0
                        && mpStatus.getSizeInBytes() >= 0
                )
                .expectComplete()
                .verify();
    }

    @Test
    void whenGetCurrentMempoolTransactions_returnsListOfTxn() {
        localTxMonitorClient.acquire().block();
        Mono<List<byte[]>> mono = localTxMonitorClient.getCurrentMempoolTransactionsAsMono();
//        List<byte[]> txns = mono.block();
//
        StepVerifier.create(mono)
                .expectNextMatches(txs -> txs.size() >= 0)
                .expectComplete()
                .verify();
    }

    @Test
    void whenGetCurrentMempoolTransactionsAsFlux_returnFlux() {
        localTxMonitorClient.acquire().block();
        Flux<byte[]> flux = localTxMonitorClient.getCurrentMempoolTransactions();
//        flux.subscribe(bytes -> System.out.println(TransactionUtil.getTxHash(bytes)));

        StepVerifier.create(flux)
                .expectComplete()
                .verify();
        ;
    }

    @Test
        //TODO -- Mock test
    void whenAcquireAndGetMempoolTransactions_returnFluxOfTransactions() {
        Flux<byte[]> flux = localTxMonitorClient.acquireAndGetMempoolTransactions();

        StepVerifier.create(flux)
                .expectComplete()
                .verify();
    }

    @Test
        //TODO-- Mock test
    void whenStreamMempoolTransactions_returnNewTransactions() throws InterruptedException {
        Flux<byte[]> flux = localTxMonitorClient.streamMempoolTransactions();
//       flux.subscribe(bytes -> {
//           System.out.println("Tx# " + TransactionUtil.getTxHash(bytes));
//       });
//
//       while(true)
//           Thread.sleep(2000);
    }

    @Test
    void testAddTxMonitorListener() throws InterruptedException {
        AtomicInteger count = new AtomicInteger(0);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        LocalClientProvider localQueryProvider = new LocalClientProvider(nodeSocketFile, protocolMagic);
        localQueryProvider.addLocalTxMonitorListener(new LocalTxMonitorListener() {
            @Override
            public void acquiredAt(MsgAwaitAcquire request, MsgAcquired msgAcquired) {
                System.out.println("ACQUIRED");
                count.incrementAndGet();
                countDownLatch.countDown();
            }
        });
        localQueryProvider.start();
        localQueryProvider.getTxMonitorClient().acquire().subscribe();
        countDownLatch.await(10, TimeUnit.SECONDS);
        assertThat(count.get()).isEqualTo(1);
    }
}
