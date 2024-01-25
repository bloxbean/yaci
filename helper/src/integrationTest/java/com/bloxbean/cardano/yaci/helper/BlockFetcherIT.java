package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.client.util.JsonUtil;
import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Redeemer;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
class BlockFetcherIT extends BaseTest {

    @Test
    public void fetchBlock() throws InterruptedException {
        VersionTable versionTable = N2NVersionTableConstant.v4AndAbove(protocolMagic);
        BlockFetcher blockFetcher = new BlockFetcher(node, nodePort, versionTable);

        CountDownLatch countDownLatch = new CountDownLatch(1);

        List<Block> blocks = new ArrayList<>();
        blockFetcher.start(block -> {
            log.info("Block >>> {} -- {} {}", block.getHeader().getHeaderBody().getBlockNumber(), block.getHeader().getHeaderBody().getSlot() + "  ", block.getEra());
            blocks.add(block);
            countDownLatch.countDown();
        });

        //Byron blocks
//        Point from = new Point(0, "f0f7892b5c333cffc4b3c4344de48af4cc63f55e44936196f365a9ef2244134f");
//        Point to = new Point(5, "365201e928da50760fce4bdad09a7338ba43a43aff1c0e8d3ec458388c932ec8");

        Point from = new Point(13006114, "86dabb90d316b104af0bb926a999fecd17c59be3fa377302790ad70495c4b509");
        Point to = new Point(13006114, "86dabb90d316b104af0bb926a999fecd17c59be3fa377302790ad70495c4b509");
        blockFetcher.fetch(from, to);

        countDownLatch.await(10, TimeUnit.SECONDS);
        blockFetcher.shutdown();

        assertThat(blocks.get(0).getHeader().getHeaderBody().getBlockNumber()).isEqualTo(287622);
    }


    @Test
    @Disabled
    public void fetchBlock_tillTip() throws InterruptedException {
        VersionTable versionTable = N2NVersionTableConstant.v4AndAbove(protocolMagic);
        BlockFetcher blockFetcher = new BlockFetcher(node, nodePort, versionTable);

        AtomicInteger count = new AtomicInteger(0);
        blockFetcher.addBlockFetchListener(new BlockfetchAgentListener() {
            @Override
            public void byronBlockFound(ByronMainBlock byronBlock) {
                if (count.incrementAndGet() % 1000 == 0)
                    System.out.println("Byron Block >> " + byronBlock.getHeader().getConsensusData().getDifficulty());
                count.incrementAndGet();
            }

            @Override
            public void blockFound(Block block) {
                if (count.incrementAndGet() % 1000 == 0)
                    System.out.println("Block >> " + block.getHeader().getHeaderBody().getBlockNumber());
            }
        });
        blockFetcher.start();

        Point from = null;
        Point to = null;
        if (protocolMagic == Constants.SANCHONET_PROTOCOL_MAGIC) {
            from = new Point(60, "8f4e50c397cf0796e6ac9b6db9fc0b761a29f1a040a7f1cfaa35513e3cc4db38");
            to = new Point(19397676, "804046ba432b676895198d2dc9ae8f0f842f7dd74b8aba71f12dc98594548361");
        } else if (protocolMagic == Constants.PREPROD_PROTOCOL_MAGIC) {
            from = new Point(2, "1d031daf47281f69cd95ab929c269fd26b1434a56a5bbbd65b7afe85ef96b233");
            to = new Point(50468813, "2fb2554a9fec38ce4b8121c001087f867b1bd19cda11e93dc5475dc253baf0e9");
        } else if (protocolMagic == Constants.MAINNET_PROTOCOL_MAGIC) {
            from = new Point(1, "1dbc81e3196ba4ab9dcb07e1c37bb28ae1c289c0707061f28b567c2f48698d50");
            to = new Point(114620634, "fc1e525bd6406a1bf01b2423ea761336546ff14fc5bb3c4b711b60f57ae143a4");
        }

        blockFetcher.fetch(from, to);

        int aliveCount = 0;
        while (true) {
            aliveCount++;
            if (aliveCount % 10 == 0) {
                int random =(int) Math.random()*(65000-0+1)+0;
                blockFetcher.sendKeepAliveMessage(random);
            }

            Thread.sleep(1000);
        }
    }

    @Test
    public void fetchBlockByron() throws InterruptedException {
        BlockFetcher blockFetcher = new BlockFetcher(node, nodePort, protocolMagic);

        CountDownLatch countDownLatch = new CountDownLatch(3);
        List<ByronMainBlock> blocks = new ArrayList<>();
        blockFetcher.addBlockFetchListener(new BlockfetchAgentListener() {
            @Override
            public void byronBlockFound(ByronMainBlock byronBlock) {
                System.out.println("Byron Block >> " + byronBlock.getHeader().getConsensusData());
                blocks.add(byronBlock);
                countDownLatch.countDown();
            }
        });

        blockFetcher.start();

        Point from = new Point(4325, "f3d7cd6f93cb4c59b61b28ac974f4a4dccfc44a4c83c1998aad17bb6b7b03446");
        Point to = new Point(8641, "f5441700216e5516c6dc19e7eb616f0bf1d04dd1368add35e3a7fd114e30b880");
        blockFetcher.fetch(from, to);

        countDownLatch.await(100, TimeUnit.SECONDS);
        blockFetcher.shutdown();

        assertThat(blocks).hasSize(3);
        assertThat(blocks.get(0).getHeader().getBlockHash()).isEqualTo("f3d7cd6f93cb4c59b61b28ac974f4a4dccfc44a4c83c1998aad17bb6b7b03446");
        assertThat(blocks.get(1).getHeader().getBlockHash()).isEqualTo("ab0a64eaf8bc9e96e4df7161d3ace4d32e88cab2315f952665807509e49892eb");
        assertThat(blocks.get(2).getHeader().getBlockHash()).isEqualTo("f5441700216e5516c6dc19e7eb616f0bf1d04dd1368add35e3a7fd114e30b880");
    }

    @Test
    public void fetchBlock_verifyRedeemerCbor_whenSerDeserMismatch() throws InterruptedException {
        VersionTable versionTable = N2NVersionTableConstant.v4AndAbove(Constants.PREVIEW_PROTOCOL_MAGIC);
        BlockFetcher blockFetcher = new BlockFetcher(Constants.PREVIEW_IOHK_RELAY_ADDR, Constants.PREVIEW_IOHK_RELAY_PORT, versionTable);

        CountDownLatch countDownLatch = new CountDownLatch(1);

        AtomicReference<Redeemer> redeemerAtomicReference = new AtomicReference<>();
        blockFetcher.start(block -> {
            log.info("Block >>> {} -- {} {}", block.getHeader().getHeaderBody().getBlockNumber(), block.getHeader().getHeaderBody().getSlot() + "  ", block.getEra());
            redeemerAtomicReference.set(block.getTransactionWitness().get(0).getRedeemers().get(0));
            System.out.println(JsonUtil.getPrettyJson(block));
            countDownLatch.countDown();
        });

        Point from = new Point(2340288, "7b1e3f57a73c5656599de4f38b0f44b78c7ea650e07a81e6cdd13a6f2ede31c4");
        Point to = new Point(2340288, "7b1e3f57a73c5656599de4f38b0f44b78c7ea650e07a81e6cdd13a6f2ede31c4");
        blockFetcher.fetch(from, to);

        countDownLatch.await(10, TimeUnit.SECONDS);
        blockFetcher.shutdown();

        var redeemer = redeemerAtomicReference.get();
        assertThat(redeemer.getCbor()).isEqualTo("840100d8799fbfff01ff821a0008efb61a125066ec");
        assertThat(redeemer.getData().getCbor()).isEqualTo("d8799fbfff01ff");
    }

    /** Not able to fetch block 0
     @Test
     public void fetchGenesisBlockByron() throws InterruptedException {
     BlockFetcher blockFetcher = new BlockFetcher(node, nodePort, protocolMagic);

     CountDownLatch countDownLatch = new CountDownLatch(2);
     List<ByronBlock> blocks = new ArrayList<>();
     blockFetcher.addBlockFetchListener(new BlockfetchAgentListener() {
     @Override
     public void byronBlockFound(ByronMainBlock byronBlock) {
     System.out.println("Byron Block >> " + byronBlock.getHeader().getConsensusData());
     blocks.add(byronBlock);
     countDownLatch.countDown();
     }

     @Override
     public void byronEbBlockFound(ByronEbBlock byronEbBlock) {
     System.out.println("Byron Block >> " + byronEbBlock.getHeader().getConsensusData());
     blocks.add(byronEbBlock);
     countDownLatch.countDown();
     }
     });

     blockFetcher.start();

     Point from = new Point(0, "9ad7ff320c9cf74e0f5ee78d22a85ce42bb0a487d0506bf60cfb5a91ea4497d2");
     Point to = new Point(1, "1d031daf47281f69cd95ab929c269fd26b1434a56a5bbbd65b7afe85ef96b233");
     blockFetcher.fetch(from, to);

     countDownLatch.await(100, TimeUnit.SECONDS);
     blockFetcher.shutdown();

     assertThat(blocks).hasSize(3);
     assertThat(blocks.get(0).getHeader().getBlockHash()).isEqualTo("9ad7ff320c9cf74e0f5ee78d22a85ce42bb0a487d0506bf60cfb5a91ea4497d2");
     assertThat(blocks.get(1).getHeader().getBlockHash()).isEqualTo("ab0a64eaf8bc9e96e4df7161d3ace4d32e88cab2315f952665807509e49892eb");
     }
     **/
}
