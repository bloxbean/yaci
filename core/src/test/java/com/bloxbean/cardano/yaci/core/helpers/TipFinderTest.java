package com.bloxbean.cardano.yaci.core.helpers;

import com.bloxbean.cardano.yaci.core.BaseTest;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
//@EnabledIfEnvironmentVariable(named = "INT_TEST", matches = "true")
class TipFinderTest extends BaseTest {

    @Test
    void findTip_usingCallback() {
        TipFinder tipFinder = new TipFinder(node, nodePort, knownPoint, protocolMagic);

        tipFinder.start(tip -> {
            System.out.println("Tip found >> " + tip);
            assertThat(tip).isNotNull();
        });

        tipFinder.shutdown();
    }

    @Test
    void findTip() {
        TipFinder tipFinder = new TipFinder(node, nodePort, knownPoint, N2NVersionTableConstant.v4AndAbove(protocolMagic));

        for (int i=0; i<10; i++) {
            Mono<Tip> mono = tipFinder.find();
            Tip tip = mono.block();
            System.out.println("Tip >> " + tip);
            assertThat(tip).isNotNull();
        }
        tipFinder.shutdown();
    }

}
