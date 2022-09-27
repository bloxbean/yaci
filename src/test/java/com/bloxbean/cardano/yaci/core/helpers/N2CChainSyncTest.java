package com.bloxbean.cardano.yaci.core.helpers;

import com.bloxbean.cardano.yaci.core.common.Constants;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@EnabledIfEnvironmentVariable(named = "INT_TEST", matches = "true")
public class N2CChainSyncTest {

    @Test
    @Timeout(value = 40000, unit = TimeUnit.MILLISECONDS)
    void syncFromLatest() throws InterruptedException {
        N2CChainSyncFetcher chainSyncFetcher = new N2CChainSyncFetcher("/Users/satya/work/cardano-node/preview/db/node.socket", Constants.WELL_KNOWN_PREVIEW_POINT, Constants.PREVIEW_PROTOCOL_MAGIC);

        AtomicBoolean atomicBoolean = new AtomicBoolean(false);
        chainSyncFetcher.start(block -> {
            log.info(">>>> Block >>>> " + block.getHeader().getHeaderBody().getBlockNumber());
            atomicBoolean.set(true);
        });

        while(!atomicBoolean.get())
            Thread.sleep(1000);

        chainSyncFetcher.shutdown();
    }
}
