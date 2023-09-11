package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
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
        CountDownLatch countDownLatch = new CountDownLatch(1);
        StartPoint startPoint = new StartPoint();
        blockSync.startSync(Point.ORIGIN, new BlockChainDataListener() {
            @Override
            public void onByronEbBlock(ByronEbBlock byronEbBlock) {
                startPoint.setFirstBlock(new Point(0, byronEbBlock.getHeader().getBlockHash()));
                startPoint.setFirstBlockEra(Era.Byron);

                startPoint.setGenesisHash(byronEbBlock.getHeader().getPrevBlock());
                countDownLatch.countDown();
            }

            @Override
            public void onBlock(Block block) {
                if (block.getHeader().getHeaderBody().getBlockNumber() == 0) {
                    startPoint.setFirstBlock(new Point(block.getHeader().getHeaderBody().getSlot(), block.getHeader().getHeaderBody().getBlockHash()));
                    startPoint.setFirstBlockEra(block.getEra());

                    startPoint.setGenesisHash(block.getHeader().getHeaderBody().getPrevHash());
                    countDownLatch.countDown();
                }
            }
        });

        try {
            countDownLatch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {

        }
        blockSync.stop();

        if (startPoint.getFirstBlock() == null)
            return Optional.empty();
        else
            return Optional.of(startPoint);
    }
}
