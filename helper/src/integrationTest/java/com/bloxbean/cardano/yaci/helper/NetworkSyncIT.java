package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.exception.BlockParseRuntimeException;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in full-history network sync tests.
 *
 * <p>The {@code YACI_FULL_SYNC} environment variable prevents these long-running tests from starting during a
 * normal integration-test run. Set it to {@code true} and select one method with Gradle:</p>
 * <pre>{@code
 * YACI_FULL_SYNC=true ./gradlew :helper:integrationTest \
 *     --tests 'com.bloxbean.cardano.yaci.helper.NetworkSyncIT.syncMainnet'
 *
 * YACI_FULL_SYNC=true ./gradlew :helper:integrationTest \
 *     --tests 'com.bloxbean.cardano.yaci.helper.NetworkSyncIT.syncPreprod'
 *
 * YACI_FULL_SYNC=true ./gradlew :helper:integrationTest \
 *     --tests 'com.bloxbean.cardano.yaci.helper.NetworkSyncIT.syncPreview'
 *
 * YACI_FULL_SYNC=true ./gradlew :helper:integrationTest \
 *     --tests 'com.bloxbean.cardano.yaci.helper.NetworkSyncIT.syncSanchonet'
 * }</pre>
 */
@Slf4j
@EnabledIfEnvironmentVariable(named = "YACI_FULL_SYNC", matches = "true")
class NetworkSyncIT {
    private static final Duration TIP_TIMEOUT = Duration.ofSeconds(30);
    private static final long SYNC_TIMEOUT_HOURS = 12;
    private static final long LOG_INTERVAL = 10_000;

    /** Syncs mainnet from the earliest available repository intersection to a tip captured at test start. */
    @Test
    void syncMainnet() throws InterruptedException {
        sync(new Network(
                "mainnet",
                Constants.MAINNET_PUBLIC_RELAY_ADDR,
                Constants.MAINNET_PUBLIC_RELAY_PORT,
                Constants.MAINNET_PROTOCOL_MAGIC,
                Constants.WELL_KNOWN_MAINNET_POINT,
                // The first mainnet point currently accepted by the public relay for a complete range sync.
                new Point(2, "52b7912de176ab76c233d6e08ccdece53ac1863c08cc59d3c5dec8d924d9b536")));
    }

    /** Syncs preprod from the earliest available repository intersection to a tip captured at test start. */
    @Test
    void syncPreprod() throws InterruptedException {
        sync(new Network(
                "preprod",
                Constants.PREPROD_PUBLIC_RELAY_ADDR,
                Constants.PREPROD_PUBLIC_RELAY_PORT,
                Constants.PREPROD_PROTOCOL_MAGIC,
                Constants.WELL_KNOWN_PREPROD_POINT,
                // This is the last Byron point before the Shelley-onward preprod history.
                new Point(8641, "f5441700216e5516c6dc19e7eb616f0bf1d04dd1368add35e3a7fd114e30b880")));
    }

    /** Syncs preview from its first block to a tip captured at test start. */
    @Test
    void syncPreview() throws InterruptedException {
        sync(new Network(
                "preview",
                Constants.PREVIEW_PUBLIC_RELAY_ADDR,
                Constants.PREVIEW_PUBLIC_RELAY_PORT,
                Constants.PREVIEW_PROTOCOL_MAGIC,
                Constants.WELL_KNOWN_PREVIEW_POINT,
                // Preview starts in Alonzo at block 1; this point identifies that first block.
                new Point(20, "cd619529ca62b4c37f7f728cd6d3472682115f001e1d1278bf1b7dce528db44e")));
    }

    /** Syncs Sanchonet from its first available block to a tip captured at test start. */
    @Test
    void syncSanchonet() throws InterruptedException {
        sync(new Network(
                "sanchonet",
                Constants.SANCHONET_PUBLIC_RELAY_ADDR,
                Constants.SANCHONET_PUBLIC_RELAY_PORT,
                Constants.SANCHONET_PROTOCOL_MAGIC,
                Constants.WELL_KNOWN_SANCHONET_POINT,
                // This point identifies the first Sanchonet block currently used by the repository sync helper.
                new Point(40, "70c15bed339afa78e87de8b4b436c8d2a9b61753d76978a2194b23462c89120b")));
    }

    /**
     * Captures a stable target tip, streams the configured history, and verifies that no block was lost to a
     * parsing error.
     *
     * @param network relay and intersection details for the network under test
     */
    private void sync(Network network) throws InterruptedException {
        Tip tip = findTip(network);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicLong blocks = new AtomicLong();
        AtomicReference<Point> lastPoint = new AtomicReference<>();
        List<BlockParseRuntimeException> parsingErrors = new ArrayList<>();
        AtomicReference<String> rangeError = new AtomicReference<>();
        BlockFetcher blockFetcher = new BlockFetcher(network.host, network.port, network.protocolMagic);

        blockFetcher.addBlockFetchListener(new BlockfetchAgentListener() {
            @Override
            public void blockFound(Block block) {
                Point point = new Point(block.getHeader().getHeaderBody().getSlot(),
                        block.getHeader().getHeaderBody().getBlockHash());
                recordProgress(network.name, blocks, lastPoint, point,
                        block.getHeader().getHeaderBody().getBlockNumber());
            }

            @Override
            public void byronBlockFound(ByronMainBlock block) {
                Point point = new Point(block.getHeader().getConsensusData().getAbsoluteSlot(),
                        block.getHeader().getBlockHash());
                recordProgress(network.name, blocks, lastPoint, point,
                        block.getHeader().getConsensusData().getDifficulty().longValue());
            }

            @Override
            public void byronEbBlockFound(ByronEbBlock block) {
                blocks.incrementAndGet();
            }

            @Override
            public void onParsingError(BlockParseRuntimeException error) {
                parsingErrors.add(error);
            }

            @Override
            public void noBlockFound(Point from, Point to) {
                rangeError.set("No blocks returned from " + from + " to " + to);
                completed.countDown();
            }

            @Override
            public void batchDone() {
                completed.countDown();
            }
        });

        ScheduledExecutorService keepAlive = Executors.newSingleThreadScheduledExecutor();
        try {
            blockFetcher.start();
            keepAlive.scheduleAtFixedRate(() -> blockFetcher.sendKeepAliveMessage(
                    (int) (System.nanoTime() & 0xffff)), 20, 20, TimeUnit.SECONDS);
            blockFetcher.fetch(network.from, tip.getPoint());

            assertThat(completed.await(SYNC_TIMEOUT_HOURS, TimeUnit.HOURS))
                    .as("%s full sync should complete within %s hours", network.name, SYNC_TIMEOUT_HOURS)
                    .isTrue();
        } finally {
            keepAlive.shutdownNow();
            blockFetcher.shutdown();
        }

        assertThat(rangeError.get()).isNull();
        assertThat(parsingErrors).as("block parsing errors").isEmpty();
        assertThat(lastPoint.get()).as("last Shelley-onward point").isEqualTo(tip.getPoint());
        assertThat(blocks.get()).isPositive();
    }

    /**
     * Finds the target tip before starting the block-fetch range.
     *
     * @param network network whose current tip is required
     * @return tip captured from the same relay used for the full sync
     */
    private Tip findTip(Network network) {
        TipFinder tipFinder = new TipFinder(network.host, network.port, network.wellKnownPoint,
                network.protocolMagic);
        try {
            Tip tip = tipFinder.find().block(TIP_TIMEOUT);
            assertThat(tip).as("%s tip", network.name).isNotNull();
            return tip;
        } finally {
            tipFinder.shutdown();
        }
    }

    /**
     * Updates the latest point and logs periodic progress without retaining parsed blocks in memory.
     *
     * @param network network name used in the progress message
     * @param blocks total delivered block callbacks
     * @param lastPoint latest Shelley or Byron main-block point
     * @param point point delivered by the current callback
     * @param blockNumber ledger block number or Byron difficulty
     */
    private void recordProgress(String network, AtomicLong blocks, AtomicReference<Point> lastPoint,
                                Point point, long blockNumber) {
        lastPoint.set(point);
        long count = blocks.incrementAndGet();
        if (count % LOG_INTERVAL == 0) {
            log.info("{} full sync: callbacks={}, block={}, slot={}, hash={}",
                    network, count, blockNumber, point.getSlot(), point.getHash());
        }
    }

    /** Relay and chain points needed by one independently runnable network test. */
    @AllArgsConstructor
    private static class Network {
        private final String name;
        private final String host;
        private final int port;
        private final long protocolMagic;
        private final Point wellKnownPoint;
        private final Point from;
    }
}
