package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.helper.model.Transaction;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@Disabled
public class ConwayEraIT extends BaseTest{

    @Test
    void syncTest() throws Exception {
        BlockSync blockSync = new BlockSync(Constants.SANCHONET_PUBLIC_RELAY_ADDR, Constants.SANCHONET_PUBLIC_RELAY_PORT, Constants.SANCHONET_PROTOCOL_MAGIC, Constants.WELL_KNOWN_SANCHONET_POINT);
        CountDownLatch countDownLatch = new CountDownLatch(5);
        List<Block> blocks = new ArrayList<>();
        blockSync.startSync(Point.ORIGIN, new BlockChainDataListener() {
            public void onBlock(Era era, Block block, List<Transaction> transactions) {
                System.out.println(block.getHeader().getHeaderBody().getBlockNumber());
                System.out.println(block.getHeader().getHeaderBody().getBlockHash());
                System.out.println(block.getHeader().getHeaderBody().getSlot());
                System.out.println("# of transactions >> " + transactions.size());
                blocks.add(block);
                countDownLatch.countDown();
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

        countDownLatch.await(30, TimeUnit.SECONDS);
        assertThat(blocks).hasSize(5);
    }

    @Test
    void syncFromTip() throws Exception {
        BlockSync blockSync = new BlockSync(Constants.SANCHONET_PUBLIC_RELAY_ADDR, Constants.SANCHONET_PUBLIC_RELAY_PORT, Constants.SANCHONET_PROTOCOL_MAGIC, Constants.WELL_KNOWN_SANCHONET_POINT);

        CountDownLatch countDownLatch = new CountDownLatch(1);
        List<Block> blocks = new ArrayList<>();
        blockSync.startSyncFromTip(new BlockChainDataListener() {
            public void onBlock(Era era, Block block, List<Transaction> transactions) {
                System.out.println(block.getHeader().getHeaderBody().getBlockNumber());
                System.out.println(block.getHeader().getHeaderBody().getBlockHash());
                System.out.println(block.getHeader().getHeaderBody().getSlot());
                System.out.println("# of transactions >> " + transactions.size());
                System.out.println("Voting Procedures: " + transactions.stream().map(t -> t.getBody().getVotingProcedures()).collect(Collectors.toList()));
                System.out.println("Proposal Procedures: " + transactions.stream().map(t -> t.getBody().getProposalProcedures()).collect(Collectors.toList()));
                System.out.println("Current Treasury Value: " + transactions.stream().map(t -> t.getBody().getCurrentTreasuryValue()).collect(Collectors.toList()));
                System.out.println("Dontation: " + transactions.stream().map(t -> t.getBody().getDonation()).collect(Collectors.toList()));
                blocks.add(block);
                countDownLatch.countDown();
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

        countDownLatch.await(60, TimeUnit.SECONDS);
        assertThat(blocks).hasSize(1);
    }

    @Test
    public void fetchBlock() throws InterruptedException {
        BlockFetcher blockFetcher = new BlockFetcher(Constants.SANCHONET_PUBLIC_RELAY_ADDR, Constants.SANCHONET_PUBLIC_RELAY_PORT, Constants.SANCHONET_PROTOCOL_MAGIC);

        CountDownLatch countDownLatch = new CountDownLatch(1);
        AtomicInteger count = new AtomicInteger(0);
        blockFetcher.start(block -> {
            if (count.get() % 1000 == 0)
                log.info("Block >>> {} -- {} -- {} -- {}", block.getHeader().getHeaderBody().getBlockNumber(),
                        block.getHeader().getHeaderBody().getBlockHash(),
                        block.getHeader().getHeaderBody().getSlot(), block.getEra());

            count.incrementAndGet();
            countDownLatch.countDown();
        });

        TipFinder tipFinder = new TipFinder(Constants.SANCHONET_PUBLIC_RELAY_ADDR, Constants.SANCHONET_PUBLIC_RELAY_PORT, Constants.WELL_KNOWN_SANCHONET_POINT, Constants.SANCHONET_PROTOCOL_MAGIC);
        Tip tip = tipFinder.find().block(Duration.ofSeconds(5));

        Point from = Constants.WELL_KNOWN_SANCHONET_POINT;
        Point to = tip.getPoint();
        blockFetcher.fetch(from, to);

        countDownLatch.await(30, TimeUnit.SECONDS);

        blockFetcher.shutdown();
        assertThat(count.get()).isGreaterThanOrEqualTo(1);
    }
}
