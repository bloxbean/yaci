package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.helper.model.Transaction;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BlockRangeSyncIT extends BaseTest{

    @Test
    void fetch() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(3);
        List<Block> blocks = new ArrayList<>();
        BlockRangeSync blockRangeSync = new BlockRangeSync(node, nodePort, protocolMagic);
        blockRangeSync.start(new BlockChainDataListener() {
            @Override
            public void onBlock(Era era, Block block, List<Transaction> transactions) {
                System.out.println(block.getHeader().getHeaderBody().getBlockNumber());
                blocks.add(block);
                countDownLatch.countDown();
            }
        });

        Point from = new Point(13107194, "5b6194ab222088fdd0f3bad7e7343808ec10e52db107160e412bc204f58cf020");
        Point to = new Point(13107220, "b26af1198cb891107fafead2388c8e8019f0157d101fda7896ba7358b58c7b83");
        blockRangeSync.fetch(from, to);

        countDownLatch.await(60, TimeUnit.SECONDS);
        blockRangeSync.stop();
        assertThat(blocks).hasSize(3);
        assertThat(blocks.get(0).getHeader().getHeaderBody().getSlot()).isEqualTo(13107194);
        assertThat(blocks.get(2).getHeader().getHeaderBody().getSlot()).isEqualTo(13107220);
    }

    @Test
    @Disabled
    void fetch_tillTip() throws InterruptedException {
        BlockRangeSync blockRangeSync = new BlockRangeSync(node, nodePort, protocolMagic);
        AtomicInteger blockCount = new AtomicInteger(0);
        blockRangeSync.start(new BlockChainDataListener() {
            @Override
            public void onBlock(Era era, Block block, List<Transaction> transactions) {
                int count = blockCount.incrementAndGet();
                if (count % 1000 == 0)
                    System.out.println("Block: " + block.getHeader().getHeaderBody().getBlockNumber());
            }
        });

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

        blockRangeSync.fetch(from, to);

        while (true) {
            int min = 1;
            int max = 65000;
            int randomNum = (int)(Math.random() * (max - min + 1)) + min;
            blockRangeSync.sendKeepAliveMessage(randomNum);

            System.out.println("Last Keep Alive Message Time : " + blockRangeSync.getLastKeepAliveResponseTime());
            System.out.println("Last Keep Alive Message Cookie : " + blockRangeSync.getLastKeepAliveResponseCookie());

            Thread.sleep(2000);
        }
    }
}
