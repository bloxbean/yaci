package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2c.LocalChainSyncAgentListener;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@EnabledIfEnvironmentVariable(named = "INT_TEST", matches = "true")
public class N2CChainSyncIT {

    @Test
    void testChainSync_fromOrigin_byronBlocks() throws InterruptedException {
        N2CChainSyncFetcher chainSyncFetcher = new N2CChainSyncFetcher("/Users/satya/work/cardano-node/prepod/db/node.socket", Point.ORIGIN, Constants.PREPROD_PROTOCOL_MAGIC, false);

        List<ByronEbBlock> genesisBlock = new ArrayList<>();
        List<ByronMainBlock> byronBlocks = new ArrayList<>();
        CountDownLatch countDownLatch = new CountDownLatch(3);
        chainSyncFetcher.addChainSyncListener(new LocalChainSyncAgentListener() {
            @Override
            public void rollforwardByronEra(Tip tip, ByronMainBlock byronMainBlock) {
                byronBlocks.add(byronMainBlock);
                countDownLatch.countDown();
            }

            @Override
            public void rollforwardByronEra(Tip tip, ByronEbBlock byronEbBlock) {
                genesisBlock.add(byronEbBlock);
                countDownLatch.countDown();
            }
        });

        chainSyncFetcher.start();
        countDownLatch.await(60, TimeUnit.SECONDS);
        chainSyncFetcher.shutdown();

        assertThat(byronBlocks).hasSizeGreaterThanOrEqualTo(3);
        assertThat(byronBlocks.get(0).getClass()).isEqualTo(ByronEbBlock.class);
        assertThat(genesisBlock.get(0).getHeader().getConsensusData().getEpoch()).isEqualTo(0);
        assertThat(genesisBlock.get(0).getHeader().getBlockHash()).isEqualTo("9ad7ff320c9cf74e0f5ee78d22a85ce42bb0a487d0506bf60cfb5a91ea4497d2");
        assertThat(byronBlocks.get(0).getHeader().getConsensusData().getSlotId().getSlot()).isEqualTo(2);
        assertThat(byronBlocks.get(0).getHeader().getBlockHash()).isEqualTo("1d031daf47281f69cd95ab929c269fd26b1434a56a5bbbd65b7afe85ef96b233");
        assertThat(byronBlocks.get(1).getHeader().getConsensusData().getSlotId().getSlot()).isEqualTo(2163);
        assertThat(byronBlocks.get(1).getHeader().getBlockHash()).isEqualTo("9972ffaee13b4afcf1a133434161ce25e8ecaf34b7a76e06b0c642125cf911a9");
    }

    @Test
    void testChainSync_fromRecent() throws InterruptedException {
        N2CChainSyncFetcher chainSyncFetcher = new N2CChainSyncFetcher("/Users/satya/work/cardano-node/prepod/db/node.socket", Point.ORIGIN, Constants.PREPROD_PROTOCOL_MAGIC, true);

        List<Block> blocks = new ArrayList<>();
        CountDownLatch countDownLatch = new CountDownLatch(1);
        chainSyncFetcher.addChainSyncListener(new LocalChainSyncAgentListener() {
            @Override
            public void rollforward(Tip tip, Block block) {
                blocks.add(block);
                System.out.println(block);
                countDownLatch.countDown();
            }
        });
        chainSyncFetcher.start();
        countDownLatch.await(60, TimeUnit.SECONDS);
        chainSyncFetcher.shutdown();

        assertThat(blocks.get(0).getHeader().getHeaderBody().getBlockNumber()).isGreaterThan(287480);
    }
}
