package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.exception.BlockParseRuntimeException;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.helper.model.Transaction;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

            public void onBlock(Era era, Block block, List<Transaction> transactions) {
                System.out.println(block.getHeader().getHeaderBody().getBlockNumber());
                blockNo.set(block.getHeader().getHeaderBody().getBlockNumber());
                System.out.println("# of transactions >> " + transactions.size());
                countDownLatch.countDown();
            }

        });

        countDownLatch.await(60, TimeUnit.SECONDS);
        assertThat(blockNo.get()).isGreaterThan(420800);
    }

    @Test
    @Disabled
    void syncFromTip_dontStop() throws InterruptedException {
        BlockSync blockSync = new BlockSync(node, nodePort, protocolMagic, Constants.WELL_KNOWN_PREPROD_POINT);

        blockSync.startSyncFromTip(new BlockChainDataListener() {

            public void onBlock(Era era, Block block, List<Transaction> transactions) {
                System.out.println(block.getHeader().getHeaderBody().getBlockNumber());
                System.out.println("# of transactions >> " + transactions.size());
            }

        });

        int aliveCount = 0;
        while (true) {
            aliveCount++;
            if (aliveCount % 10 == 0) {
                int min = 1;
                int max = 65000;
                int randomNum = (int)(Math.random() * (max - min + 1)) + min;
                blockSync.sendKeepAliveMessage(randomNum);
            }

            System.out.println("Last Keep Alive Message Time : " + blockSync.getLastKeepAliveResponseTime());
            System.out.println("Last Keep Alive Message Cookie : " + blockSync.getLastKeepAliveResponseCookie());
            Thread.sleep(2000);
        }
    }

    @Test
    void syncFromPoint() throws InterruptedException {
        BlockSync blockSync = new BlockSync(node, nodePort, protocolMagic, Constants.WELL_KNOWN_PREPROD_POINT);

        AtomicLong blockNo = new AtomicLong();
        AtomicInteger noOfTxs = new AtomicInteger();
        CountDownLatch countDownLatch = new CountDownLatch(2);
        blockSync.startSync(new Point(13107195, "ad2ceec67a07069d6e9295ed2144015860602c29f42505dc6ea2f55b9fc0dd93"),
                new BlockChainDataListener() {
            @Override
            public void onBlock(Era era, Block block, List<Transaction> transactions) {
                System.out.println(block.getHeader().getHeaderBody().getBlockNumber());
                if (blockNo.get() == 0)
                    blockNo.set(block.getHeader().getHeaderBody().getBlockNumber());
                System.out.println("# of transactions >> " + transactions.size());
                noOfTxs.set(transactions.size());
                countDownLatch.countDown();
            }
        });

        countDownLatch.await(60, TimeUnit.SECONDS);
        assertThat(blockNo.get()).isEqualTo(292458);
        assertThat(noOfTxs.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void intersactNotFound() throws InterruptedException {
        BlockSync blockSync = new BlockSync(node, nodePort, protocolMagic, Constants.WELL_KNOWN_PREPROD_POINT);

        AtomicBoolean success = new AtomicBoolean(false);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        blockSync.startSync(new Point(38474115, "784e0c913ce6378208f4fc1abf2ce74e817048306247e0ecffa6dac676ce8c65"),
                new BlockChainDataListener() {
                    @Override
                    public void intersactNotFound(Tip tip) {
                        System.out.println(">>> Intersection not found");
                        success.set(true);
                        countDownLatch.countDown();
                    }
                });

        countDownLatch.await(60, TimeUnit.SECONDS);
        assertThat(success.get()).isTrue();
    }

    @Test
    void intersactFound() throws InterruptedException {
        BlockSync blockSync = new BlockSync(node, nodePort, protocolMagic, Constants.WELL_KNOWN_PREPROD_POINT);

        AtomicBoolean success = new AtomicBoolean(false);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        blockSync.startSync(new Point(38481569, "cf884b8e61126190f6e59d71ad53c561f620cf65ed09aff468149b32b537a804"),
                new BlockChainDataListener() {
                    @Override
                    public void intersactFound(Tip tip, Point point) {
                        System.out.println("Intersection found");
                        success.set(true);
                        countDownLatch.countDown();
                    }
                });

        countDownLatch.await(60, TimeUnit.SECONDS);
        assertThat(success.get()).isTrue();
    }

    @Test
    void syncFromPoint_continueOnParseError() throws InterruptedException {
        BlockSync blockSync = new BlockSync(Constants.PREVIEW_PUBLIC_RELAY_ADDR, Constants.PREPROD_PUBLIC_RELAY_PORT,
                Constants.PREVIEW_PROTOCOL_MAGIC, Constants.WELL_KNOWN_PREVIEW_POINT);

        AtomicLong blockNo = new AtomicLong();
        CountDownLatch countDownLatch = new CountDownLatch(10);
        blockSync.startSync(new Point(82394373, "49033a53f777ce932be005539b01ef4d7e3b49ee4ae2f315342a182f3281384a"),
                new BlockChainDataListener() {
                    @Override
                    public void onBlock(Era era, Block block, List<Transaction> transactions) {
                        System.out.println(block.getHeader().getHeaderBody().getBlockNumber());
                        blockNo.set(block.getHeader().getHeaderBody().getBlockNumber());
                        countDownLatch.countDown();
                    }

                    @Override
                    public void onParsingError(BlockParseRuntimeException e) {
                        System.out.println("ERROR BLock: " + e.getBlockNumber());
                        System.out.println("CBOR: " + HexUtil.encodeHexString(e.getBlockCbor()));
                    }
                });

        countDownLatch.await(60, TimeUnit.SECONDS);
        assertThat(blockNo.get()).isGreaterThan(3300404 + 5);
    }
}
