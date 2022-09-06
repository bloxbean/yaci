package com.bloxbean.cardano.yaci.core.helpers;

import com.bloxbean.cardano.yaci.core.BaseTest;
import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2CVersionTableConstant;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Disabled
class LocalTipFinderTest extends BaseTest {

    @Test
    void findTip() throws InterruptedException {
        LocalTipFinder localTipFinder = new LocalTipFinder(nodeSocketFile, Constants.WELL_KNOWN_PREVIEW_POINT, N2CVersionTableConstant.v1AndAbove(Constants.PREVIEW_PROTOCOL_MAGIC));

        for (int i=0; i< 1000; i++) {
            Mono<Tip> tipMono = localTipFinder.find();
            Tip tip = tipMono.block();

            System.out.println("Tip Found $$$$$$$ " + tip);
        }
    }

    @Test
    void findTip_usingCallback() throws InterruptedException {
        LocalTipFinder localTipFinder = new LocalTipFinder(nodeSocketFile, Constants.WELL_KNOWN_PREVIEW_POINT, Constants.PREVIEW_PROTOCOL_MAGIC);
        CountDownLatch countDownLatch = new CountDownLatch(10);
        localTipFinder.start(tip -> {
            System.out.println("Tip found >>>> " + tip);
            countDownLatch.countDown();
            //Invoke next to find again
            localTipFinder.next();
        });

        countDownLatch.await(30, TimeUnit.SECONDS);
        localTipFinder.shutdown();
    }

}
