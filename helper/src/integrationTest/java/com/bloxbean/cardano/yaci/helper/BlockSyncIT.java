package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.helper.model.Transaction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

public class BlockSyncIT extends BaseTest {

    @Test
    void syncFromTip() throws InterruptedException {
        BlockSync blockSync = new BlockSync(node, nodePort, protocolMagic, Constants.WELL_KNOWN_PREPROD_POINT);

        AtomicLong blockNo = new AtomicLong();
        CountDownLatch countDownLatch = new CountDownLatch(1);
        blockSync.startSyncFromTip(new BlockChainDataListener() {
            @Override
            public void onBlock(Block block) {
                System.out.println(block.getHeader().getHeaderBody().getBlockNumber());
                blockNo.set(block.getHeader().getHeaderBody().getBlockNumber());
                countDownLatch.countDown();
            }

            @Override
            public void onTransactions(Era era, BlockHeader blockHeader, List<Transaction> transactions) {
                System.out.println("# of transactions >> " + transactions.size());
            }
        });

        countDownLatch.await(60, TimeUnit.SECONDS);
        assertThat(blockNo.get()).isGreaterThan(420800);
    }

    @Test
    void syncFromPoint() throws InterruptedException {
        BlockSync blockSync = new BlockSync(node, nodePort, protocolMagic, Constants.WELL_KNOWN_PREPROD_POINT);

        AtomicLong blockNo = new AtomicLong();
        AtomicInteger noOfTxs = new AtomicInteger();
        CountDownLatch countDownLatch = new CountDownLatch(4);
        blockSync.startSync(new Point(13107195, "ad2ceec67a07069d6e9295ed2144015860602c29f42505dc6ea2f55b9fc0dd93"),
                new BlockChainDataListener() {
            @Override
            public void onBlock(Block block) {
                System.out.println(block.getHeader().getHeaderBody().getBlockNumber());
                if (blockNo.get() == 0)
                    blockNo.set(block.getHeader().getHeaderBody().getBlockNumber());
                countDownLatch.countDown();
            }

            @Override
            public void onTransactions(Era era, BlockHeader blockHeader, List<Transaction> transactions) {
                System.out.println("# of transactions >> " + transactions.size());
                noOfTxs.set(transactions.size());
                countDownLatch.countDown();
            }
        });

        countDownLatch.await(60, TimeUnit.SECONDS);
        assertThat(blockNo.get()).isEqualTo(292458);
        assertThat(noOfTxs.get()).isGreaterThanOrEqualTo(1);
    }
}
