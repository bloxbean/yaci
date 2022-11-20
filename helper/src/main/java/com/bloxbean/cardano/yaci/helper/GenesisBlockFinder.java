package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.helper.model.StartPoint;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class GenesisBlockFinder {
    private BlockSync blockSync;
    public GenesisBlockFinder(String host, int port, long protocolMagic) {
        blockSync = new BlockSync(host, port, protocolMagic, Point.ORIGIN);
    }

    public Optional<StartPoint> getGenesisAndFirstBlock() {
        CountDownLatch countDownLatch = new CountDownLatch(2);
        StartPoint startPoint = new StartPoint();
        blockSync.startSync(Point.ORIGIN, new BlockChainDataListener() {
            @Override
            public void onByronBlock(ByronMainBlock byronBlock) {
                startPoint.setFirstBlock(new Point(byronBlock.getHeader().getConsensusData().getSlotId().getSlot(),
                        byronBlock.getHeader().getBlockHash()));
                countDownLatch.countDown();
            }

            @Override
            public void onByronEbBlock(ByronEbBlock byronEbBlock) {
                startPoint.setGenesisBlock(new Point(0, byronEbBlock.getHeader().getBlockHash()));
                countDownLatch.countDown();
            }

            @Override
            public void onBlock(Block block) {
                System.out.println(block);
            }
        });

        try {
            countDownLatch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {

        }
        blockSync.stop();

        if (startPoint.getFirstBlock() == null || startPoint.getGenesisBlock() == null)
            return Optional.empty();
        else
            return Optional.of(startPoint);
    }
}
