package com.bloxbean.cardano.yaci.core.helpers;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.byron.ByronBlock;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Slf4j
@Disabled
class BlockFetcherTest {

    @Test
    public void fetchBlock() throws InterruptedException {
        VersionTable versionTable = N2NVersionTableConstant.v4AndAbove(1);
        BlockFetcher blockFetcher = new BlockFetcher("prepod-node.world.dev.cardano.org", 30000, versionTable);

        blockFetcher.addBlockFetchListener(new BlockfetchAgentListener() {
            int counter = 0;
            @Override
            public void batchDone() {
                log.info("Batch Done ------>");
            }

            @Override
            public void blockFound(Block block) {
                log.info("BLOCK FOUND >> {}", block);
            }

            @Override
            public void byronBlockFound(ByronBlock byronBlock) {
                log.info("Byron BLOCK FOUND >>  {}", byronBlock.getHeader().getConsensusData().getSlotId());
            }

            @Override
            public void readyForNextBatch() {

            }
        });

        blockFetcher.start(block -> {
            log.info("Block >>> {} -- {} {}", block.getHeader().getHeaderBody().getBlockNumber(), block.getHeader().getHeaderBody().getSlot() + "  ", block.getEra());
        });

        Point from = new Point(5393295, "fe67a56efb34ff7d2a381cf7e7ee37a42bedc5baa38dbc7e00c3d04d0924fe97");
        Point to = new Point(5393295, "fe67a56efb34ff7d2a381cf7e7ee37a42bedc5baa38dbc7e00c3d04d0924fe97");
        blockFetcher.fetch(from, to);

        while(true)
            Thread.sleep(2000);
    }
}
