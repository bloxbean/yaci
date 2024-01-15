package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class NetworkSyncIT {

    public static void syncSanchonet() throws InterruptedException {
        BlockFetcher blockFetcher = new BlockFetcher(Constants.SANCHONET_PUBLIC_RELAY_ADDR, Constants.SANCHONET_PUBLIC_RELAY_PORT, Constants.SANCHONET_PROTOCOL_MAGIC);

        AtomicInteger count = new AtomicInteger(0);
        blockFetcher.start(block -> {
            if (count.get() % 100 == 0)
                log.info("Block >>> {} -- {} -- {} -- {}", block.getHeader().getHeaderBody().getBlockNumber(),
                        block.getHeader().getHeaderBody().getBlockHash(),
                        block.getHeader().getHeaderBody().getSlot(), block.getEra());

            count.incrementAndGet();
        });

        TipFinder tipFinder = new TipFinder(Constants.SANCHONET_PUBLIC_RELAY_ADDR, Constants.SANCHONET_PUBLIC_RELAY_PORT, Constants.WELL_KNOWN_SANCHONET_POINT, Constants.SANCHONET_PROTOCOL_MAGIC);
        Tip tip = tipFinder.find().block(Duration.ofSeconds(5));

        Point from = new Point(40, "70c15bed339afa78e87de8b4b436c8d2a9b61753d76978a2194b23462c89120b");
        Point to = tip.getPoint();
        blockFetcher.fetch(from, to);

        while (true)
            Thread.sleep(2000);
    }

    public static void syncPreprod() throws InterruptedException {
        BlockFetcher blockFetcher = new BlockFetcher(Constants.PREPROD_IOHK_RELAY_ADDR, Constants.PREPROD_IOHK_RELAY_PORT, Constants.PREPROD_PROTOCOL_MAGIC);

        AtomicInteger count = new AtomicInteger(0);
        blockFetcher.start(block -> {
            if (count.get() % 100 == 0)
                log.info("Block >>> {} -- {} -- {} -- {}", block.getHeader().getHeaderBody().getBlockNumber(),
                        block.getHeader().getHeaderBody().getBlockHash(),
                        block.getHeader().getHeaderBody().getSlot(), block.getEra());

            count.incrementAndGet();
        });

        TipFinder tipFinder = new TipFinder(Constants.PREPROD_IOHK_RELAY_ADDR, Constants.PREPROD_IOHK_RELAY_PORT, Constants.WELL_KNOWN_PREPROD_POINT, Constants.PREPROD_PROTOCOL_MAGIC);
        Tip tip = tipFinder.find().block(Duration.ofSeconds(5));

        Point from = new Point(8641, "f5441700216e5516c6dc19e7eb616f0bf1d04dd1368add35e3a7fd114e30b880");
        Point to = tip.getPoint();
        blockFetcher.fetch(from, to);

        while (true)
            Thread.sleep(2000);
    }

    public static void syncMainnet() throws InterruptedException {
        BlockFetcher blockFetcher = new BlockFetcher(Constants.MAINNET_IOHK_RELAY_ADDR, Constants.MAINNET_IOHK_RELAY_PORT, Constants.MAINNET_PROTOCOL_MAGIC);

        AtomicInteger count = new AtomicInteger(0);
        blockFetcher.addBlockFetchListener(new BlockfetchAgentListener() {
            @Override
            public void blockFound(Block block) {
                if (count.get() % 100 == 0)
                    log.info("Block >>> {} -- {} -- {} -- {}", block.getHeader().getHeaderBody().getBlockNumber(),
                            block.getHeader().getHeaderBody().getBlockHash(),
                            block.getHeader().getHeaderBody().getSlot(), block.getEra());

                count.incrementAndGet();
            }

            @Override
            public void byronBlockFound(ByronMainBlock byronBlock) {
                if (count.get() % 100 == 0)
                    log.info("Block >>> {} -- {} -- {} -- {}", byronBlock.getHeader().getConsensusData().getDifficulty(),
                            byronBlock.getHeader().getBlockHash(),
                            byronBlock.getHeader().getConsensusData().getAbsoluteSlot(), "Byron");

                count.incrementAndGet();
            }
        });

        blockFetcher.start();

        TipFinder tipFinder = new TipFinder(Constants.MAINNET_IOHK_RELAY_ADDR, Constants.MAINNET_IOHK_RELAY_PORT, Constants.WELL_KNOWN_MAINNET_POINT, Constants.MAINNET_PROTOCOL_MAGIC);
        Tip tip = tipFinder.find().block(Duration.ofSeconds(5));

        Point from = new Point(2, "52b7912de176ab76c233d6e08ccdece53ac1863c08cc59d3c5dec8d924d9b536");
        Point to = tip.getPoint();
        blockFetcher.fetch(from, to);

        while (true)
            Thread.sleep(2000);
    }


    public static void syncPreview() throws InterruptedException {
        BlockFetcher blockFetcher = new BlockFetcher(Constants.PREVIEW_IOHK_RELAY_ADDR, Constants.PREVIEW_IOHK_RELAY_PORT, Constants.PREVIEW_PROTOCOL_MAGIC);

        AtomicInteger count = new AtomicInteger(0);
        blockFetcher.addBlockFetchListener(new BlockfetchAgentListener() {
            @Override
            public void blockFound(Block block) {
                if (count.get() % 100 == 0)
                    log.info("Block >>> {} -- {} -- {} -- {}", block.getHeader().getHeaderBody().getBlockNumber(),
                            block.getHeader().getHeaderBody().getBlockHash(),
                            block.getHeader().getHeaderBody().getSlot(), block.getEra());

                count.incrementAndGet();
            }

            @Override
            public void byronBlockFound(ByronMainBlock byronBlock) {
                if (count.get() % 100 == 0)
                    log.info("Block >>> {} -- {} -- {} -- {}", byronBlock.getHeader().getConsensusData().getDifficulty(),
                            byronBlock.getHeader().getBlockHash(),
                            byronBlock.getHeader().getConsensusData().getAbsoluteSlot(), "Byron");

                count.incrementAndGet();
            }
        });
        blockFetcher.start();

        TipFinder tipFinder = new TipFinder(Constants.PREVIEW_IOHK_RELAY_ADDR, Constants.PREVIEW_IOHK_RELAY_PORT, Constants.WELL_KNOWN_PREVIEW_POINT, Constants.PREVIEW_PROTOCOL_MAGIC);
        Tip tip = tipFinder.find().block(Duration.ofSeconds(5));

        Point from = new Point(20, "cd619529ca62b4c37f7f728cd6d3472682115f001e1d1278bf1b7dce528db44e");
        Point to = tip.getPoint();

        blockFetcher.fetch(from, to);

        while (true) {
            blockFetcher.sendKeepAliveMessage(getRandomNumber(0, 65535));
            Thread.sleep(3000);
        }
    }

    public static int getRandomNumber(int min, int max) {
        return (int) ((Math.random() * (max - min)) + min);
    }

    public static void main(String[] args) throws Exception {
        syncPreprod();
    }

}
