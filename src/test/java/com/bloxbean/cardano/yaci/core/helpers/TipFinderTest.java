package com.bloxbean.cardano.yaci.core.helpers;

import com.bloxbean.cardano.yaci.core.BaseTest;
import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import reactor.core.publisher.Mono;

@Slf4j
@EnabledIfEnvironmentVariable(named = "INT_TEST", matches = "true")
class TipFinderTest extends BaseTest {

    @Test
    void findTip_usingCallback() throws InterruptedException {
        TipFinder tipFinder = new TipFinder(node, nodePort, Constants.WELL_KNOWN_MAINNET_POINT, Constants.MAINNET_PROTOCOL_MAGIC);

        tipFinder.start(tip -> {
            System.out.println("Tip found >> " + tip);
        });

        tipFinder.shutdown();
    }

    @Test
    void findTip() throws InterruptedException {
        TipFinder tipFinder = new TipFinder(node, nodePort, Constants.WELL_KNOWN_MAINNET_POINT, N2NVersionTableConstant.v4AndAbove(Constants.MAINNET_PROTOCOL_MAGIC));

        for (int i=0; i<10; i++) {
            Mono<Tip> mono = tipFinder.find();
            Tip tip = mono.block();
            System.out.println("Tip >> " + tip);
        }
        tipFinder.shutdown();
    }

}
