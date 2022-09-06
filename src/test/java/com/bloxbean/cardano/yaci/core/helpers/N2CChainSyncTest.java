package com.bloxbean.cardano.yaci.core.helpers;

import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2CVersionTableConstant;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Disabled
public class N2CChainSyncTest {

    @Test
    @Timeout(value = 40000, unit = TimeUnit.MILLISECONDS)
    void syncFromLatest() throws InterruptedException {
        VersionTable versionTable = N2CVersionTableConstant.v1AndAbove(2);
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
