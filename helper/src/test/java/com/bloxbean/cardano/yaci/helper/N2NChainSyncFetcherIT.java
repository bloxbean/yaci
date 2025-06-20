package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
//@EnabledIfEnvironmentVariable(named = "INT_TEST", matches = "true")
public class N2NChainSyncFetcherIT {

    @Test
    void testChainSync_fromOrigin_byronBlocks() throws InterruptedException {
        N2NChainSyncFetcher chainSyncFetcher = new N2NChainSyncFetcher(Constants.PREPROD_IOHK_RELAY_ADDR, Constants.PREPROD_IOHK_RELAY_PORT,
                Point.ORIGIN, 1, false);

        List<ByronEbBlock> genesisBlock = new ArrayList<>();
        List<ByronMainBlock> byronBlocks = new ArrayList<>();
        CountDownLatch countDownLatch = new CountDownLatch(3);
        chainSyncFetcher.addBlockFetchListener(new BlockfetchAgentListener() {
            @Override
            public void byronEbBlockFound(ByronEbBlock byronEbBlock) {
                genesisBlock.add(byronEbBlock);
                countDownLatch.countDown();
            }

            @Override
            public void byronBlockFound(ByronMainBlock byronBlock) {
                byronBlocks.add(byronBlock);
                countDownLatch.countDown();
            }

        });

        chainSyncFetcher.start(block -> {
            log.info(">>>> Block >>>> " + block.getHeader().getHeaderBody().getBlockNumber());
        });

        countDownLatch.await(20, TimeUnit.SECONDS);
        chainSyncFetcher.shutdown();

        assertThat(byronBlocks).hasSizeGreaterThanOrEqualTo(2);
        assertThat((genesisBlock.get(0)).getHeader().getConsensusData().getEpoch()).isEqualTo(0);
        assertThat((genesisBlock.get(0)).getHeader().getBlockHash()).isEqualTo("9ad7ff320c9cf74e0f5ee78d22a85ce42bb0a487d0506bf60cfb5a91ea4497d2");
        assertThat(byronBlocks.get(0).getHeader().getConsensusData().getSlotId().getSlot()).isEqualTo(2);
        assertThat(byronBlocks.get(0).getHeader().getBlockHash()).isEqualTo("1d031daf47281f69cd95ab929c269fd26b1434a56a5bbbd65b7afe85ef96b233");
        assertThat(byronBlocks.get(1).getHeader().getConsensusData().getSlotId().getSlot()).isEqualTo(2163);
        assertThat(byronBlocks.get(1).getHeader().getBlockHash()).isEqualTo("9972ffaee13b4afcf1a133434161ce25e8ecaf34b7a76e06b0c642125cf911a9");

    }

//    @Test
    void testChainSync_fromRecent() throws InterruptedException {
        N2NChainSyncFetcher chainSyncFetcher = new N2NChainSyncFetcher(Constants.PREPROD_IOHK_RELAY_ADDR, Constants.PREPROD_IOHK_RELAY_PORT,
                Point.ORIGIN, 1);

        List<Block> blocks = new ArrayList<>();
        CountDownLatch countDownLatch = new CountDownLatch(1);
        chainSyncFetcher.addBlockFetchListener(new BlockfetchAgentListener() {
            @Override
            public void blockFound(Block block) {
                blocks.add(block);
                countDownLatch.countDown();
            }
        });
        chainSyncFetcher.start();
        countDownLatch.await(60, TimeUnit.SECONDS);
        chainSyncFetcher.shutdown();

        System.out.println(blocks.get(0));
        assertThat(blocks.get(0).getHeader().getHeaderBody().getBlockNumber()).isGreaterThan(287480);
    }

    @Test
    void testChainSync_continueOnParseError() throws InterruptedException {
        N2NChainSyncFetcher chainSyncFetcher = new N2NChainSyncFetcher(Constants.PREVIEW_PUBLIC_RELAY_ADDR,
                Constants.PREVIEW_PUBLIC_RELAY_PORT,
                new Point(82394373, "49033a53f777ce932be005539b01ef4d7e3b49ee4ae2f315342a182f3281384a"),
                Constants.PREVIEW_PROTOCOL_MAGIC, false);

        List<Block> blocks = new ArrayList<>();
        CountDownLatch countDownLatch = new CountDownLatch(10);
        chainSyncFetcher.addBlockFetchListener(new BlockfetchAgentListener() {
            @Override
            public void blockFound(Block block) {
                blocks.add(block);
                countDownLatch.countDown();
            }
        });
        chainSyncFetcher.start();
        countDownLatch.await(60, TimeUnit.SECONDS);
        chainSyncFetcher.shutdown();

        System.out.println(blocks.get(0));
        assertThat(blocks).hasSizeGreaterThan(8);
    }
}
