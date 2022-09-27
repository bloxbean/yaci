package com.bloxbean.cardano.yaci.core.helpers;

import com.bloxbean.cardano.yaci.core.BaseTest;
import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.byron.ByronBlock;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Slf4j
@EnabledIfEnvironmentVariable(named = "INT_TEST", matches = "true")
class BlockFetcherTest extends BaseTest {

    @Test
    public void fetchBlock() throws InterruptedException {
        VersionTable versionTable = N2NVersionTableConstant.v4AndAbove(Constants.MAINNET_PROTOCOL_MAGIC);
        BlockFetcher blockFetcher = new BlockFetcher(node, nodePort, versionTable);

        CountDownLatch countDownLatch = new CountDownLatch(1);
        blockFetcher.start(block -> {
            log.info("Block >>> {} -- {} {}", block.getHeader().getHeaderBody().getBlockNumber(), block.getHeader().getHeaderBody().getSlot() + "  ", block.getEra());
            countDownLatch.countDown();
        });

        //Byron blocks
//        Point from = new Point(0, "f0f7892b5c333cffc4b3c4344de48af4cc63f55e44936196f365a9ef2244134f");
//        Point to = new Point(5, "365201e928da50760fce4bdad09a7338ba43a43aff1c0e8d3ec458388c932ec8");

        Point from = new Point(33914577, "d9c6a8314457e3f8a8204c1d8c26854e377df101326e529a5d1a7cd27dd101e1");
        Point to = new Point(33914577, "d9c6a8314457e3f8a8204c1d8c26854e377df101326e529a5d1a7cd27dd101e1");
        blockFetcher.fetch(from, to);

        countDownLatch.await(10, TimeUnit.SECONDS);
        blockFetcher.shutdown();
    }

    @Test
    public void fetchBlock_constructorWithProtocolMagic() throws InterruptedException {
        BlockFetcher blockFetcher = new BlockFetcher(node, nodePort, Constants.MAINNET_PROTOCOL_MAGIC);

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

        CountDownLatch countDownLatch = new CountDownLatch(7);
        blockFetcher.start(block -> {
            //  log.info(JsonUtil.getPrettyJson(block));
            log.info("Block >>> {} -- {} {}", block.getHeader().getHeaderBody().getBlockNumber(), block.getHeader().getHeaderBody().getSlot() + "  ", block.getEra());
            countDownLatch.countDown();
        });

        Point from = new Point(70895877, "094de3242c9cc6504851c9ca1f109c379840364bb6a1a941353c87cf1f22cf06");
        Point to = new Point(70896002, "1f58983e784ff3eabd9bdb97808402086baffbf51742a120d3635df867c16ad9");
        blockFetcher.fetch(from, to);

        countDownLatch.await(5, TimeUnit.SECONDS);
        blockFetcher.shutdown();
    }
}
