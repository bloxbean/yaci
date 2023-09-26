package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.helper.model.Transaction;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class ConwayEraIT extends BaseTest{

    //@Test
    void syncTest() throws Exception {
        //BlockSync blockSync = new BlockSync(Constants.SANCHONET_PUBLIC_RELAY_ADDR, Constants.SANCHONET_PUBLIC_RELAY_PORT, Constants.SANCHONET_PROTOCOL_MAGIC, Constants.WELL_KNOWN_SANCHONET_POINT);
        BlockSync blockSync = new BlockSync(Constants.SANCHONET_PUBLIC_RELAY_ADDR, Constants.SANCHONET_PUBLIC_RELAY_PORT, Constants.SANCHONET_PROTOCOL_MAGIC, Constants.WELL_KNOWN_SANCHONET_POINT);
//        BlockSync blockSync = new BlockSync("localhost", 30000, Constants.PREPROD_PROTOCOL_MAGIC, Constants.WELL_KNOWN_PREPROD_POINT);
        blockSync.startSync(Point.ORIGIN, new BlockChainDataListener() {
            public void onBlock(Era era, Block block, List<Transaction> transactions) {
                System.out.println(block.getHeader().getHeaderBody().getBlockNumber());
                System.out.println(block.getHeader().getHeaderBody().getBlockHash());
                System.out.println(block.getHeader().getHeaderBody().getSlot());
                System.out.println("# of transactions >> " + transactions.size());
            }

            @Override
            public void onByronBlock(ByronMainBlock byronBlock) {
                System.out.println("Byron block: " + byronBlock);
            }

            @Override
            public void onByronEbBlock(ByronEbBlock byronEbBlock) {
                System.out.println("Byron EB block: " + byronEbBlock);
            }
        });

        while (true)
            Thread.sleep(5000);
    }

    @Test
    void syncFromTip() throws Exception {
        //BlockSync blockSync = new BlockSync(Constants.SANCHONET_PUBLIC_RELAY_ADDR, Constants.SANCHONET_PUBLIC_RELAY_PORT, Constants.SANCHONET_PROTOCOL_MAGIC, Constants.WELL_KNOWN_SANCHONET_POINT);
        BlockSync blockSync = new BlockSync(Constants.SANCHONET_PUBLIC_RELAY_ADDR, Constants.SANCHONET_PUBLIC_RELAY_PORT, Constants.WELL_KNOWN_SANCHONET_POINT,
                N2NVersionTableConstant.v11AndAbove(Constants.SANCHONET_PROTOCOL_MAGIC));
//        BlockSync blockSync = new BlockSync("localhost", 30000, Constants.PREPROD_PROTOCOL_MAGIC, Constants.WELL_KNOWN_PREPROD_POINT);
        blockSync.startSyncFromTip(new BlockChainDataListener() {
            public void onBlock(Era era, Block block, List<Transaction> transactions) {
                System.out.println(block.getHeader().getHeaderBody().getBlockNumber());
                System.out.println(block.getHeader().getHeaderBody().getBlockHash());
                System.out.println(block.getHeader().getHeaderBody().getSlot());
                System.out.println("# of transactions >> " + transactions.size());
            }

            @Override
            public void onByronBlock(ByronMainBlock byronBlock) {
                System.out.println("Byron block: " + byronBlock);
            }

            @Override
            public void onByronEbBlock(ByronEbBlock byronEbBlock) {
                System.out.println("Byron EB block: " + byronEbBlock);
            }
        });

        while (true)
            Thread.sleep(5000);
    }

//    @Test
    public void fetchBlock() throws InterruptedException {
        VersionTable versionTable = N2NVersionTableConstant.v4AndAbove(Constants.SANCHONET_PROTOCOL_MAGIC);
        BlockFetcher blockFetcher = new BlockFetcher(Constants.SANCHONET_PUBLIC_RELAY_ADDR, Constants.SANCHONET_PUBLIC_RELAY_PORT, versionTable);

        CountDownLatch countDownLatch = new CountDownLatch(1);

        AtomicInteger count = new AtomicInteger(0);

//        List<Block> blocks = new ArrayList<>();
        blockFetcher.start(block -> {
            if (count.get() % 1000 == 0)
                log.info("Block >>> {} -- {} -- {} -- {}", block.getHeader().getHeaderBody().getBlockNumber(),
                        block.getHeader().getHeaderBody().getBlockHash(),
                        block.getHeader().getHeaderBody().getSlot(), block.getEra());

            count.incrementAndGet();
//            countDownLatch.countDown();

        });
        Point knownPoint = new Point(20, "6a7d97aae2a65ca790fd14802808b7fce00a3362bd7b21c4ed4ccb4296783b98");
        TipFinder tipFinder = new TipFinder(Constants.SANCHONET_PUBLIC_RELAY_ADDR, Constants.SANCHONET_PUBLIC_RELAY_PORT, Constants.WELL_KNOWN_SANCHONET_POINT, versionTable);
        Tip tip = tipFinder.find().block(Duration.ofSeconds(5));
        //Byron blocks
//        Point from = new Point(0, "f0f7892b5c333cffc4b3c4344de48af4cc63f55e44936196f365a9ef2244134f");
//        Point to = new Point(5, "365201e928da50760fce4bdad09a7338ba43a43aff1c0e8d3ec458388c932ec8");

        Point from = Constants.WELL_KNOWN_SANCHONET_POINT;
        Point to = tip.getPoint();//new Point(8569937, "8264c74dcdf7afc02e0c176090af367b2662326d623a478710d54e22bf749ebd");
        blockFetcher.fetch(from, to);

        while (true)
            Thread.sleep(5000);
//        blockFetcher.shutdown();

//        assertThat(blocks.get(0).getHeader().getHeaderBody().getBlockNumber()).isEqualTo(287622);
    }
}
