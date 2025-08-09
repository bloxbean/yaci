package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.helper.model.Transaction;
import org.junit.jupiter.api.Test;

import java.util.List;

class PeerClientTest {

    @Test
    void startSync() throws InterruptedException {
        PeerClient peerClient = new PeerClient("localhost",
                32000,
                Constants.PREPROD_PROTOCOL_MAGIC, Constants.WELL_KNOWN_PREPROD_POINT);

//        var countDownLatch = new CountDownLatch(2);
        peerClient.connect(new BlockChainDataListener() {
            @Override
            public void onRollback(Point point) {
                    System.out.println("PeerClientTest.onRollback: " + point);
            }

            @Override
            public void onBlock(Era era, Block block, List<Transaction> transactions) {
                System.out.println("Peerclient.block : " + block.getHeader().getHeaderBody().getBlockNumber());
//                countDownLatch.countDown();
            }

            @Override
            public void onByronEbBlock(ByronEbBlock byronEbBlock) {
                System.out.println("PeerClientTest.onByronEbBlock: " + byronEbBlock.getHeader().getConsensusData().getDifficulty());
            }

            @Override
            public void onByronBlock(ByronMainBlock byronBlock) {
                System.out.println("PeerClientTest.onByronBlock: " + byronBlock.getHeader().getConsensusData().getDifficulty());
            }

            public void intersactFound(Tip tip, Point point) {
                System.out.println("PeerClientTest.intersactFound: " + tip + ", " + point);
            }

            @Override
            public void rollforward(Tip tip, BlockHeader blockHeader, byte[] originalHeaderBytes) {
                System.out.println("PeerClientTest.rollforward: " + blockHeader.getHeaderBody().getBlockNumber());
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }

        }, null);

        // Use wellKnownPoint instead of ORIGIN to test reconnection properly
        peerClient.startHeaderSync(Constants.WELL_KNOWN_PREPROD_POINT, true);

//        countDownLatch.await(3, TimeUnit.SECONDS);

        while(true)
            Thread.sleep(3000);
    }
}
