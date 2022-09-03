package com.bloxbean.cardano.yaci.core.helpers;

import com.bloxbean.cardano.yaci.core.common.Constants;
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
        VersionTable versionTable = N2NVersionTableConstant.v4AndAbove(Constants.MAINNET_PROTOCOL_MAGIC);
        BlockFetcher blockFetcher = new BlockFetcher("192.168.0.228", 6000, versionTable);

        blockFetcher.addBlockFetchListener(new BlockfetchAgentListener() {
            int counter = 0;
            @Override
            public void batchDone() {
                log.info("Batch Done ------>");
            }

            @Override
            public void blockFound(Block block) {
//                log.info("BLOCK FOUND >> {}", block);
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
          //  log.info(JsonUtil.getPrettyJson(block));
            log.info("Block >>> {} -- {} {}", block.getHeader().getHeaderBody().getBlockNumber(), block.getHeader().getHeaderBody().getSlot() + "  ", block.getEra());
        });

        Point from = new Point(33914577, "d9c6a8314457e3f8a8204c1d8c26854e377df101326e529a5d1a7cd27dd101e1");
        Point to = new Point(33914577, "d9c6a8314457e3f8a8204c1d8c26854e377df101326e529a5d1a7cd27dd101e1");
        blockFetcher.fetch(from, to);

        while(true)
            Thread.sleep(2000);
    }
}
