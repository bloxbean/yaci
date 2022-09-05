package com.bloxbean.cardano.yaci.core.helpers;

import com.bloxbean.cardano.yaci.core.BaseTest;
import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

@Disabled
class LocalTipFinderTest extends BaseTest {

    @Test
    void findTip() throws InterruptedException {
        LocalTipFinder localTipFinder = new LocalTipFinder(nodeSocketFile, Constants.WELL_KNOWN_PREVIEW_POINT, Constants.PREVIEW_PROTOCOL_MAGIC);

        for (int i=0; i< 1000; i++) {
            Mono<Tip> tipMono = localTipFinder.find();
            Tip tip = tipMono.block();
            System.out.println("Tip Found $$$$$$$ " + tip);
        }
    }

}
