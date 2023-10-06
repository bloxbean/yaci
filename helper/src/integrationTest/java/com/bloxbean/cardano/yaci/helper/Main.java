package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class Main {
    public void start() throws InterruptedException {
        VersionTable versionTable = N2NVersionTableConstant.v4AndAbove(Constants.MAINNET_PROTOCOL_MAGIC);
        BlockFetcher blockFetcher = new BlockFetcher(Constants.MAINNET_IOHK_RELAY_ADDR, Constants.MAINNET_IOHK_RELAY_PORT, versionTable);

        YaciConfig.INSTANCE.setReturnBlockCbor(true);
        YaciConfig.INSTANCE.setReturnTxBodyCbor(true);

        AtomicLong counter = new AtomicLong(0);
        blockFetcher.addBlockFetchListener(new BlockfetchAgentListener() {
            @Override
            public void blockFound(Block block) {
                long count = counter.incrementAndGet();
                if (count % 1000 == 0)
                    log.info("Block >>> {} -- {} {}", block.getHeader().getHeaderBody().getBlockNumber(), block.getHeader().getHeaderBody().getSlot() + "  ", block.getEra());
            }

            @Override
            public void byronBlockFound(ByronMainBlock byronBlock) {
                long count = counter.incrementAndGet();
                if (count % 1000 == 0)
                    log.info("Block >>> {} -- {} {}", byronBlock.getHeader().getBlockHash(), byronBlock.getHeader().getConsensusData().getDifficulty(), "Byron");
            }

            @Override
            public void byronEbBlockFound(ByronEbBlock byronEbBlock) {
                long count = counter.incrementAndGet();
                if (count % 1000 == 0)
                    log.info("Block >>> {} -- {} {}", byronEbBlock.getHeader().getBlockHash(), byronEbBlock.getHeader().getConsensusData().getDifficulty(), "Byron");
            }
        });

        blockFetcher.start();

        //Byron blocks
//        Point from = new Point(0, "f0f7892b5c333cffc4b3c4344de48af4cc63f55e44936196f365a9ef2244134f");
//        Point to = new Point(5, "365201e928da50760fce4bdad09a7338ba43a43aff1c0e8d3ec458388c932ec8");

        Point from = new Point(0, "f0f7892b5c333cffc4b3c4344de48af4cc63f55e44936196f365a9ef2244134f");
//        Point from = new Point(58481235, "579ba004e3f2c71be9f951d874dfc4f73347195fb70ffaf109c18bdcad18ccfe");
//      Point from = new Point(46115813, "a127cae7db121119afca2a6c4ff517c177861fc53bfd36f7368b5aadce091c21");
//      Point to = new Point(46115813, "a127cae7db121119afca2a6c4ff517c177861fc53bfd36f7368b5aadce091c21");
        Point to = new Point(104746008, "7002f8805ed2969d47fce0dc62ca2c1bf52f1c56c3d8cce8e1e838fea1742fbc");
        blockFetcher.fetch(from, to);

        while(true)
            Thread.sleep(3000);
    }

    public static void main(String[] args) throws InterruptedException {
        new Main().start();
    }
}
