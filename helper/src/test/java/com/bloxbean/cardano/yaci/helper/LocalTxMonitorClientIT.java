package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.protocol.localtxmonitor.LocalTxMonitorListener;
import com.bloxbean.cardano.yaci.core.protocol.localtxmonitor.messages.MsgAcquired;
import com.bloxbean.cardano.yaci.core.protocol.localtxmonitor.messages.MsgAwaitAcquire;
import com.bloxbean.cardano.yaci.helper.model.MempoolStatus;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@EnabledIfEnvironmentVariable(named = "INT_TEST", matches = "true")
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

        long slot = mono.block(Duration.ofSeconds(10));
        System.out.println("Slot : " + slot);
        assertThat(slot).isGreaterThan(1000);
    }

    @Test
    void whenGetMempoolSize_returnSizeAndCapacity() {
        localTxMonitorClient.acquire().block(Duration.ofSeconds(10));
        Mono<MempoolStatus> mono = localTxMonitorClient.getMempoolSizeAndCapacity();
        MempoolStatus mempoolStatus = mono.block(Duration.ofSeconds(10));
        System.out.println("Size & Capacity : " + mempoolStatus);
        assertThat(mempoolStatus.getCapacityInBytes()).isGreaterThan(10); //some random value other than zero
        assertThat(mempoolStatus.getNumberOfTxs()).isGreaterThanOrEqualTo(0);
        assertThat(mempoolStatus.getSizeInBytes()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void whenAcquireAndGetMempoolSizeAndCapacity_returnSizeAndCapacity() {
        Mono<MempoolStatus> mono = localTxMonitorClient.acquireAndGetMempoolSizeAndCapacity();
        MempoolStatus mempoolStatus = mono.block(Duration.ofSeconds(10));
        System.out.println("Size & Capacity : " + mempoolStatus);
        assertThat(mempoolStatus.getCapacityInBytes()).isGreaterThan(10); //some random value other than zero
        assertThat(mempoolStatus.getNumberOfTxs()).isGreaterThanOrEqualTo(0);
        assertThat(mempoolStatus.getSizeInBytes()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void whenGetCurrentMempoolTransactions_returnsListOfTxn() {
        localTxMonitorClient.acquire().block();
        Mono<List<byte[]>> mono = localTxMonitorClient.getCurrentMempoolTransactions();
        List<byte[]> txns = mono.block();

        assertThat(txns).hasSize(0);
//        System.out.println(txns.size());
//        for(byte[] bytes: txns) {
//            System.out.println(TransactionUtil.getTxHash(bytes));
//        }
    }

    @Test
    void whenGetCurrentMempoolTransactionsAsFlux_returnFlux() {
        localTxMonitorClient.acquire().block();
        Flux<byte[]> flux = localTxMonitorClient.getCurrentMempoolTransactionsAsFlux();
//        flux.subscribe(bytes -> System.out.println(TransactionUtil.getTxHash(bytes)));

        StepVerifier.create(flux)
                .expectComplete()
                .verify();;
    }

    @Test
    //TODO -- Mock test
    void whenAcquireAndGetMempoolTransactions_returnFluxOfTransactions() {
        Flux<byte[]> flux = localTxMonitorClient.acquireAndGetMempoolTransactions();
//        flux.subscribe(bytes -> {
//            System.out.println(TransactionUtil.getTxHash(bytes));
//        });
//
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
    void testAddTxMonitorListener() {
        AtomicInteger count = new AtomicInteger(0);
        LocalClientProvider localQueryProvider = new LocalClientProvider(nodeSocketFile, protocolMagic);
        localQueryProvider.addLocalTxMonitorListener(new LocalTxMonitorListener() {
            @Override
            public void acquiredAt(MsgAwaitAcquire request, MsgAcquired msgAcquired) {
                System.out.println("ACQUIRED");
                count.incrementAndGet();
            }
        });
        localQueryProvider.start();
        localQueryProvider.getTxMonitorClient().acquire().block();
        assertThat(count.get()).isEqualTo(1);
    }
}
