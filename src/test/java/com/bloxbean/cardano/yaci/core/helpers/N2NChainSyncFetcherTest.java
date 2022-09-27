package com.bloxbean.cardano.yaci.core.helpers;

import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainSyncAgentListener;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@Slf4j
@EnabledIfEnvironmentVariable(named = "INT_TEST", matches = "true")
public class N2NChainSyncFetcherTest {

    @Test
    void testChainSync() throws InterruptedException {
        N2NChainSyncFetcher chainSyncFetcher = new N2NChainSyncFetcher("localhost", 3001, Point.ORIGIN, 42);

        chainSyncFetcher.addChainSyncListener(new ChainSyncAgentListener() {
            @Override
            public void rollforward(Tip tip, BlockHeader blockHeader) {
                log.info("RollForward !!!");
            }
        });

        chainSyncFetcher.start(block -> {
            log.info(">>>> Block >>>> " + block.getHeader().getHeaderBody().getBlockNumber());
        });

        while (true)
            Thread.sleep(3000);
    }
}
