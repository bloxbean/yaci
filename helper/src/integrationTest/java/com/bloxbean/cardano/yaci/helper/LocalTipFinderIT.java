package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2CVersionTableConstant;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
class LocalTipFinderIT extends BaseTest {

    @Test
    void findTip() {
        LocalTipFinder localTipFinder = new LocalTipFinder(nodeSocketFile, knownPoint, N2CVersionTableConstant.v1AndAbove(protocolMagic));

        for (int i=0; i< 1000; i++) {
            Mono<Tip> tipMono = localTipFinder.find();
            Tip tip = tipMono.block();

            System.out.println("Tip Found $$$$$$$ " + tip);
            assertThat(tip).isNotNull();
        }
    }

    @Test
    void findTip_usingCallback() throws InterruptedException {
        LocalTipFinder localTipFinder = new LocalTipFinder(nodeSocketFile, knownPoint, protocolMagic);
        CountDownLatch countDownLatch = new CountDownLatch(10);
        localTipFinder.start(tip -> {
            System.out.println("Tip found >>>> " + tip);
            countDownLatch.countDown();
            //Invoke next to find again
            localTipFinder.next();
            assertThat(tip).isNotNull();
        });

        countDownLatch.await(30, TimeUnit.SECONDS);
        localTipFinder.shutdown();
    }

}
