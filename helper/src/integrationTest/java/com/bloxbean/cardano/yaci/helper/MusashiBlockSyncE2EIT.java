package com.bloxbean.cardano.yaci.helper;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.common.EraUtil;
import com.bloxbean.cardano.yaci.core.exception.BlockParseRuntimeException;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.model.leios.EndorserBlockTxRef;
import com.bloxbean.cardano.yaci.core.model.leios.LeiosAnnouncement;
import com.bloxbean.cardano.yaci.core.model.leios.LeiosVote;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosPoint;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.helper.model.Transaction;
import com.bloxbean.cardano.yaci.helper.model.leios.EndorserBlockEvent;
import com.bloxbean.cardano.yaci.helper.model.leios.LeiosVotesEvent;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
@EnabledIfEnvironmentVariable(named = "YACI_MUSASHI_E2E", matches = "true")
class MusashiBlockSyncE2EIT {
    private static final String DEFAULT_HOST = "leios-node.play.dev.cardano.org";
    private static final int DEFAULT_PORT = 3001;
    private static final long DEFAULT_DURATION_SECONDS = 7_200;
    private static final long DEFAULT_PROGRESS_SECONDS = 60;
    private static final int DEFAULT_MAX_TXS_PER_ENDORSER_BLOCK = 512;
    private static final int DEFAULT_TXS_OFFER_WAIT_MILLIS = 2_000;
    private static final int DEFAULT_SAMPLE_LIMIT = 20;
    private static final long DEFAULT_NO_PROGRESS_TIMEOUT_SECONDS = 300;
    private static final int DEFAULT_MIN_RANKING_BLOCKS = 1;
    private static final int DEFAULT_MIN_DIJKSTRA_RANKING_BLOCKS = 1;
    private static final int DEFAULT_MIN_ENDORSER_BLOCKS = 0;
    private static final int DEFAULT_MIN_COMPLETE_ENDORSER_BLOCKS = 0;
    private static final Point DEFAULT_INTERSECT_POINT = new Point(540_887L,
            "e780814dec26ab4121f6e2cfc8bb916b8a84eeff0a4e878b00fc82e6e02b5e4a");

    @Test
    void blockSyncReceivesRankingBlocksAndLeiosEventsForConfiguredWindow() throws Exception {
        E2EConfig config = E2EConfig.fromEnv();
        Metrics metrics = new Metrics(config);
        AtomicBoolean stopping = new AtomicBoolean(false);

        LeiosConfig leiosConfig = LeiosConfig.builder()
                .mode(config.blockSyncLeiosMode)
                .fetchTxs(config.fetchTxs)
                .maxTxsPerEndorserBlock(config.maxTxsPerEndorserBlock)
                .txsOfferWaitMillis(config.txsOfferWaitMillis)
                .deliverVotes(config.deliverVotes)
                .build();

        BlockSync blockSync = new BlockSync(config.host, config.port, Constants.MUSASHI_PROTOCOL_MAGIC,
                config.intersectPoint, leiosConfig);

        Instant startedAt = Instant.now();
        metrics.startedAt.set(startedAt);
        try {
            log.info("Starting Musashi BlockSync E2E: host={}, port={}, intersectPoint={}, blockSyncLeiosMode={}, "
                            + "startFromTip={}, duration={}s, report={}",
                    config.host, config.port, config.intersectPoint, config.blockSyncLeiosMode,
                    config.startFromTip, config.durationSeconds, config.reportPath);
            if (config.startFromTip) {
                blockSync.startSyncFromTip(listener(metrics, stopping));
            } else {
                blockSync.startSync(config.intersectPoint, listener(metrics, stopping));
            }
            runWindow(blockSync, metrics, config, startedAt);
        } finally {
            stopping.set(true);
            try {
                blockSync.stop();
            } catch (Exception e) {
                metrics.addWarning("BlockSync stop failed: " + e.getMessage());
            }
            metrics.completedAt.set(Instant.now());
            writeReport(metrics);
        }

        assertAll("Musashi BlockSync E2E",
                () -> assertNull(metrics.failure.get(), () -> "Unexpected failure. See " + config.reportPath),
                () -> assertTrue(metrics.rankingBlocks.get() >= config.minRankingBlocks,
                        () -> "Expected at least " + config.minRankingBlocks + " RBs. See " + config.reportPath),
                () -> assertTrue(metrics.dijkstraRankingBlocks.get() >= config.minDijkstraRankingBlocks,
                        () -> "Expected at least " + config.minDijkstraRankingBlocks
                                + " Dijkstra RBs. See " + config.reportPath),
                () -> assertTrue(metrics.endorserBlocks.get() >= config.minEndorserBlocks,
                        () -> "Expected at least " + config.minEndorserBlocks + " EBs. See " + config.reportPath),
                () -> assertTrue(metrics.completeEndorserBlocks.get() >= config.minCompleteEndorserBlocks,
                        () -> "Expected at least " + config.minCompleteEndorserBlocks
                                + " complete EBs. See " + config.reportPath),
                () -> assertTrue(metrics.parseErrors.get() == 0,
                        () -> "Expected no parse errors. See " + config.reportPath));
    }

    private BlockChainDataListener listener(Metrics metrics, AtomicBoolean stopping) {
        return new BlockChainDataListener() {
            @Override
            public void intersactFound(Tip tip, Point point) {
                metrics.intersectionsFound.incrementAndGet();
                metrics.addSample(metrics.intersectionSamples, "point=" + point + ", tip=" + tip);
            }

            @Override
            public void intersactNotFound(Tip tip) {
                metrics.intersectionsNotFound.incrementAndGet();
                metrics.recordFailure(new IllegalStateException("Intersection not found: " + tip));
            }

            @Override
            public void onBlock(Era era, Block block, List<Transaction> transactions) {
                metrics.recordRankingBlock(era, block, transactions);
            }

            @Override
            public void onEndorserBlock(EndorserBlockEvent event) {
                metrics.recordEndorserBlock(event);
            }

            @Override
            public void onLeiosVotes(LeiosVotesEvent event) {
                metrics.recordVotes(event);
            }

            @Override
            public void onRollback(Point point) {
                metrics.rollbacks.incrementAndGet();
                metrics.addSample(metrics.rollbackSamples, point.toString());
            }

            @Override
            public void onDisconnect() {
                metrics.disconnects.incrementAndGet();
                if (!stopping.get()) {
                    metrics.addWarning("Disconnect observed; waiting for reconnect");
                }
            }

            @Override
            public void onParsingError(BlockParseRuntimeException e) {
                metrics.parseErrors.incrementAndGet();
                metrics.addSample(metrics.parseErrorSamples,
                        "block=" + e.getBlockNumber()
                                + ", " + eraTagSample(e.getBlockCbor())
                                + ", cborBytes=" + e.getBlockCbor().length
                                + ", cbor=" + HexUtil.encodeHexString(e.getBlockCbor()));
            }
        };
    }

    private static String eraTagSample(byte[] blockCbor) {
        try {
            DataItem dataItem = com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.deserializeOne(blockCbor);
            int eraTag = ((UnsignedInteger) ((Array) dataItem).getDataItems().get(0)).getValue().intValue();
            Era era = EraUtil.getEra(eraTag);
            return "eraTag=" + eraTag + ", era=" + era;
        } catch (Exception e) {
            return "eraTag=unknown";
        }
    }

    private void runWindow(BlockSync blockSync, Metrics metrics, E2EConfig config, Instant startedAt)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.durationSeconds);
        long nextProgress = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.progressSeconds);
        long nextKeepAlive = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.min(30, config.progressSeconds));
        AtomicInteger keepAliveCookie = new AtomicInteger(10_000);

        while (System.nanoTime() < deadline && metrics.failure.get() == null) {
            long now = System.nanoTime();
            if (now >= nextKeepAlive) {
                sendKeepAlive(blockSync, metrics, keepAliveCookie.incrementAndGet());
                nextKeepAlive = now + TimeUnit.SECONDS.toNanos(30);
            }
            if (config.noProgressTimeoutSeconds > 0
                    && Duration.between(metrics.lastProgressAt.get(), Instant.now()).toSeconds()
                    > config.noProgressTimeoutSeconds) {
                metrics.recordFailure(new IllegalStateException(
                        "No BlockSync/Leios progress for " + config.noProgressTimeoutSeconds + " seconds"));
                break;
            }
            if (now >= nextProgress) {
                logProgress(metrics, startedAt, false);
                nextProgress = now + TimeUnit.SECONDS.toNanos(config.progressSeconds);
            }
            TimeUnit.MILLISECONDS.sleep(500);
        }
        logProgress(metrics, startedAt, true);
    }

    private void sendKeepAlive(BlockSync blockSync, Metrics metrics, int cookie) {
        try {
            if (blockSync.isRunning()) {
                blockSync.sendKeepAliveMessage(cookie);
                metrics.keepAlivesSent.incrementAndGet();
            }
        } catch (Exception e) {
            metrics.addWarning("KeepAlive failed: " + e.getMessage());
        }
    }

    private void logProgress(Metrics metrics, Instant startedAt, boolean finalProgress) {
        long elapsedSeconds = Duration.between(startedAt, Instant.now()).toSeconds();
        log.info("Musashi E2E {}progress: elapsed={}s, rb={}, dijkstraRb={}, rbAnnouncements={}, eb={}, " +
                        "completeEb={}, voteBatches={}, votes={}, rollbacks={}, parseErrors={}, warnings={}",
                finalProgress ? "final " : "", elapsedSeconds, metrics.rankingBlocks.get(),
                metrics.dijkstraRankingBlocks.get(), metrics.rankingBlockAnnouncements.get(),
                metrics.endorserBlocks.get(), metrics.completeEndorserBlocks.get(),
                metrics.voteBatches.get(), metrics.votes.get(), metrics.rollbacks.get(),
                metrics.parseErrors.get(), metrics.warningSamples.size());
    }

    private void writeReport(Metrics metrics) throws IOException {
        E2EConfig config = metrics.config;
        Files.createDirectories(config.reportPath.getParent());
        Files.writeString(config.reportPath, metrics.toMarkdown());
        log.info("Musashi E2E report written to {}", config.reportPath);
    }

    private static class Metrics {
        private final E2EConfig config;
        private final AtomicReference<Instant> startedAt = new AtomicReference<>();
        private final AtomicReference<Instant> completedAt = new AtomicReference<>();
        private final AtomicReference<Instant> lastProgressAt = new AtomicReference<>(Instant.now());
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicInteger intersectionsFound = new AtomicInteger();
        private final AtomicInteger intersectionsNotFound = new AtomicInteger();
        private final AtomicInteger rankingBlocks = new AtomicInteger();
        private final AtomicInteger dijkstraRankingBlocks = new AtomicInteger();
        private final AtomicInteger rankingBlockAnnouncements = new AtomicInteger();
        private final AtomicInteger leiosCertifiedTrue = new AtomicInteger();
        private final AtomicInteger leiosCertifiedFalse = new AtomicInteger();
        private final AtomicInteger leiosCertifiedParentAnnouncementMatches = new AtomicInteger();
        private final AtomicInteger leiosCertifiedParentAnnouncementMismatches = new AtomicInteger();
        private final AtomicInteger endorserBlocks = new AtomicInteger();
        private final AtomicInteger completeEndorserBlocks = new AtomicInteger();
        private final AtomicInteger incompleteEndorserBlocks = new AtomicInteger();
        private final AtomicInteger endorserBlockTxRefs = new AtomicInteger();
        private final AtomicInteger endorserBlockTxs = new AtomicInteger();
        private final AtomicInteger parsedEndorserBlockTxs = new AtomicInteger();
        private final AtomicInteger unparsedEndorserBlockTxs = new AtomicInteger();
        private final AtomicInteger endorserBlockHashMatches = new AtomicInteger();
        private final AtomicInteger endorserBlockHashMismatches = new AtomicInteger();
        private final AtomicInteger txHashMembershipMatches = new AtomicInteger();
        private final AtomicInteger txHashMembershipMismatches = new AtomicInteger();
        private final AtomicInteger voteBatches = new AtomicInteger();
        private final AtomicInteger votes = new AtomicInteger();
        private final AtomicInteger unknownVotes = new AtomicInteger();
        private final AtomicInteger rollbacks = new AtomicInteger();
        private final AtomicInteger disconnects = new AtomicInteger();
        private final AtomicInteger parseErrors = new AtomicInteger();
        private final AtomicInteger keepAlivesSent = new AtomicInteger();
        private final AtomicLong firstRankingBlockNo = new AtomicLong(-1);
        private final AtomicLong lastRankingBlockNo = new AtomicLong(-1);
        private final AtomicLong firstRankingBlockSlot = new AtomicLong(-1);
        private final AtomicLong lastRankingBlockSlot = new AtomicLong(-1);
        private final AtomicLong transactions = new AtomicLong();
        private final Map<Era, AtomicInteger> rankingBlocksByEra = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> votesByFormat = new ConcurrentHashMap<>();
        private final Set<String> rankingBlockHashes = ConcurrentHashMap.newKeySet();
        private final Set<String> announcedEbHashes = ConcurrentHashMap.newKeySet();
        private final Set<String> endorserBlockHashes = ConcurrentHashMap.newKeySet();
        private final Queue<String> rankingBlockSamples = new ConcurrentLinkedQueue<>();
        private final Queue<String> announcementSamples = new ConcurrentLinkedQueue<>();
        private final Queue<String> endorserBlockSamples = new ConcurrentLinkedQueue<>();
        private final Queue<String> voteSamples = new ConcurrentLinkedQueue<>();
        private final Queue<String> rollbackSamples = new ConcurrentLinkedQueue<>();
        private final Queue<String> parseErrorSamples = new ConcurrentLinkedQueue<>();
        private final Queue<String> warningSamples = new ConcurrentLinkedQueue<>();
        private final Queue<String> intersectionSamples = new ConcurrentLinkedQueue<>();
        private final AtomicReference<PreviousRankingBlock> previousRankingBlock = new AtomicReference<>();

        private Metrics(E2EConfig config) {
            this.config = config;
        }

        private void recordRankingBlock(Era era, Block block, List<Transaction> blockTransactions) {
            HeaderBody headerBody = block.getHeader().getHeaderBody();
            markProgress();
            rankingBlocks.incrementAndGet();
            rankingBlocksByEra.computeIfAbsent(era, ignored -> new AtomicInteger()).incrementAndGet();
            if (era == Era.Dijkstra) {
                dijkstraRankingBlocks.incrementAndGet();
            }
            rankingBlockHashes.add(headerBody.getBlockHash());
            transactions.addAndGet(blockTransactions != null ? blockTransactions.size() : 0);
            firstRankingBlockNo.compareAndSet(-1, headerBody.getBlockNumber());
            firstRankingBlockSlot.compareAndSet(-1, headerBody.getSlot());
            lastRankingBlockNo.set(headerBody.getBlockNumber());
            lastRankingBlockSlot.set(headerBody.getSlot());
            addSample(rankingBlockSamples, "blockNo=" + headerBody.getBlockNumber()
                    + ", slot=" + headerBody.getSlot()
                    + ", era=" + era
                    + ", txs=" + (blockTransactions != null ? blockTransactions.size() : 0)
                    + ", hash=" + headerBody.getBlockHash());

            LeiosAnnouncement announcement = headerBody.getLeiosAnnouncement();
            if (announcement != null) {
                rankingBlockAnnouncements.incrementAndGet();
                announcedEbHashes.add(announcement.getEbHash());
                addSample(announcementSamples, "rb=" + headerBody.getBlockNumber()
                        + ", rbHash=" + headerBody.getBlockHash()
                        + ", ebHash=" + announcement.getEbHash()
                        + ", ebSize=" + announcement.getEbSize());
            }
            recordCertifiedParentInvariant(headerBody);
            previousRankingBlock.set(new PreviousRankingBlock(headerBody.getBlockNumber(),
                    headerBody.getBlockHash(), announcement != null));
        }

        private void recordCertifiedParentInvariant(HeaderBody headerBody) {
            if (Boolean.TRUE.equals(headerBody.getLeiosCertified())) {
                leiosCertifiedTrue.incrementAndGet();
                PreviousRankingBlock previous = previousRankingBlock.get();
                if (previous == null) {
                    addWarning("leiosCertified=true at rb=" + headerBody.getBlockNumber()
                            + " without a previous RB in the observed window");
                } else if (previous.hasAnnouncement()) {
                    leiosCertifiedParentAnnouncementMatches.incrementAndGet();
                } else {
                    leiosCertifiedParentAnnouncementMismatches.incrementAndGet();
                    addWarning("leiosCertified=true at rb=" + headerBody.getBlockNumber()
                            + " but previous rb=" + previous.blockNumber()
                            + " had no Leios announcement");
                }
            } else if (Boolean.FALSE.equals(headerBody.getLeiosCertified())) {
                leiosCertifiedFalse.incrementAndGet();
            }
        }

        private void recordEndorserBlock(EndorserBlockEvent event) {
            markProgress();
            endorserBlocks.incrementAndGet();
            if (event.isTxsComplete()) {
                completeEndorserBlocks.incrementAndGet();
            } else {
                incompleteEndorserBlocks.incrementAndGet();
            }

            String pointHash = pointHash(event.getPoint());
            endorserBlockHashes.add(pointHash);
            int txRefCount = event.getEndorserBlock() != null ? event.getEndorserBlock().txCount() : 0;
            endorserBlockTxRefs.addAndGet(txRefCount);
            int txCount = event.getTransactions() != null ? event.getTransactions().size() : 0;
            endorserBlockTxs.addAndGet(txCount);
            if (event.getTransactions() != null) {
                parsedEndorserBlockTxs.addAndGet((int) event.getTransactions().stream()
                        .filter(tx -> tx.isParsed()).count());
                unparsedEndorserBlockTxs.addAndGet((int) event.getTransactions().stream()
                        .filter(tx -> !tx.isParsed()).count());
            }

            if (event.getEndorserBlock() != null && event.getEndorserBlock().getComputedHash() != null) {
                if (pointHash.equals(event.getEndorserBlock().getComputedHash())) {
                    endorserBlockHashMatches.incrementAndGet();
                } else {
                    endorserBlockHashMismatches.incrementAndGet();
                    addWarning("EB computed hash mismatch for point " + pointHash);
                }
            }

            verifyTxHashMembership(event);
            addSample(endorserBlockSamples, "slot=" + event.getPoint().getSlot()
                    + ", ebHash=" + pointHash
                    + ", announcedSize=" + event.getAnnouncedEbSize()
                    + ", txRefs=" + txRefCount
                    + ", txs=" + txCount
                    + ", txsComplete=" + event.isTxsComplete()
                    + ", announcementCbor=" + (event.getAnnouncementCbor() != null));
        }

        private void verifyTxHashMembership(EndorserBlockEvent event) {
            if (event.getEndorserBlock() == null || event.getTransactions() == null
                    || event.getTransactions().isEmpty()) {
                return;
            }

            Set<String> txRefHashes = ConcurrentHashMap.newKeySet();
            for (EndorserBlockTxRef txRef : event.getEndorserBlock().getTxRefs()) {
                txRefHashes.add(txRef.getTxHash());
            }
            event.getTransactions().stream()
                    .filter(tx -> tx.getTxHash() != null)
                    .forEach(tx -> {
                        if (txRefHashes.contains(tx.getTxHash())) {
                            txHashMembershipMatches.incrementAndGet();
                        } else {
                            txHashMembershipMismatches.incrementAndGet();
                            addWarning("Fetched EB tx hash is not present in tx-ref map: " + tx.getTxHash());
                        }
                    });
        }

        private void recordVotes(LeiosVotesEvent event) {
            markProgress();
            voteBatches.incrementAndGet();
            if (event.getVotes() == null) {
                return;
            }

            votes.addAndGet(event.getVotes().size());
            for (LeiosVote vote : event.getVotes()) {
                String format = vote.getFormat() != null ? vote.getFormat().name() : "null";
                votesByFormat.computeIfAbsent(format, ignored -> new AtomicInteger()).incrementAndGet();
                if (vote.getFormat() == null || "UNKNOWN".equals(format)) {
                    unknownVotes.incrementAndGet();
                }
                addSample(voteSamples, "format=" + format + ", voter=" + vote.getVoterId()
                        + ", slot=" + vote.getSlot()
                        + ", ebHash=" + vote.getEbHash()
                        + ", announcingRbHash=" + vote.getAnnouncingRbHash());
            }
        }

        private void recordFailure(Throwable throwable) {
            failure.compareAndSet(null, throwable);
            addWarning("Failure: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        }

        private void markProgress() {
            lastProgressAt.set(Instant.now());
        }

        private void addWarning(String warning) {
            addSample(warningSamples, warning);
        }

        private void addSample(Queue<String> samples, String value) {
            if (samples.size() < config.sampleLimit) {
                samples.add(value);
            }
        }

        private String toMarkdown() {
            StringBuilder builder = new StringBuilder();
            boolean passed = failure.get() == null
                    && rankingBlocks.get() >= config.minRankingBlocks
                    && dijkstraRankingBlocks.get() >= config.minDijkstraRankingBlocks
                    && endorserBlocks.get() >= config.minEndorserBlocks
                    && completeEndorserBlocks.get() >= config.minCompleteEndorserBlocks
                    && parseErrors.get() == 0;

            builder.append("# Musashi BlockSync E2E Report\n\n");
            builder.append("- Status: ").append(passed ? "PASS" : "FAIL").append('\n');
            builder.append("- Started: ").append(startedAt.get()).append('\n');
            builder.append("- Completed: ").append(completedAt.get()).append('\n');
            builder.append("- Duration seconds: ").append(durationSeconds()).append('\n');
            builder.append("- Last progress: ").append(lastProgressAt.get()).append('\n');
            builder.append("- Host: ").append(config.host).append(':').append(config.port).append('\n');
            builder.append("- Intersect point: ").append(config.intersectPoint).append('\n');
            builder.append("- Report: ").append(config.reportPath).append("\n\n");

            appendConfig(builder);
            appendSummary(builder);
            appendSamples(builder, "Intersections", intersectionSamples);
            appendSamples(builder, "Ranking Block Samples", rankingBlockSamples);
            appendSamples(builder, "RB Announcement Samples", announcementSamples);
            appendSamples(builder, "Endorser Block Samples", endorserBlockSamples);
            appendSamples(builder, "Vote Samples", voteSamples);
            appendSamples(builder, "Rollback Samples", rollbackSamples);
            appendSamples(builder, "Parse Error Samples", parseErrorSamples);
            appendSamples(builder, "Warnings", warningSamples);
            if (failure.get() != null) {
                builder.append("## Failure\n\n");
                builder.append("`").append(failure.get()).append("`\n");
            }
            return builder.toString();
        }

        private void appendConfig(StringBuilder builder) {
            builder.append("## Config\n\n");
            builder.append("- durationSeconds: ").append(config.durationSeconds).append('\n');
            builder.append("- progressSeconds: ").append(config.progressSeconds).append('\n');
            builder.append("- noProgressTimeoutSeconds: ").append(config.noProgressTimeoutSeconds).append('\n');
            builder.append("- intersectPoint: ").append(config.intersectPoint).append('\n');
            builder.append("- startFromTip: ").append(config.startFromTip).append('\n');
            builder.append("- blockSyncLeiosMode: ").append(config.blockSyncLeiosMode).append('\n');
            builder.append("- fetchTxs: ").append(config.fetchTxs).append('\n');
            builder.append("- maxTxsPerEndorserBlock: ").append(config.maxTxsPerEndorserBlock).append('\n');
            builder.append("- txsOfferWaitMillis: ").append(config.txsOfferWaitMillis).append('\n');
            builder.append("- deliverVotes: ").append(config.deliverVotes).append('\n');
            builder.append("- minRankingBlocks: ").append(config.minRankingBlocks).append('\n');
            builder.append("- minDijkstraRankingBlocks: ").append(config.minDijkstraRankingBlocks).append('\n');
            builder.append("- minEndorserBlocks: ").append(config.minEndorserBlocks).append('\n');
            builder.append("- minCompleteEndorserBlocks: ").append(config.minCompleteEndorserBlocks).append("\n\n");
        }

        private void appendSummary(StringBuilder builder) {
            builder.append("## Summary\n\n");
            builder.append("- intersectionsFound: ").append(intersectionsFound.get()).append('\n');
            builder.append("- intersectionsNotFound: ").append(intersectionsNotFound.get()).append('\n');
            builder.append("- rankingBlocks: ").append(rankingBlocks.get()).append('\n');
            builder.append("- uniqueRankingBlocks: ").append(rankingBlockHashes.size()).append('\n');
            builder.append("- dijkstraRankingBlocks: ").append(dijkstraRankingBlocks.get()).append('\n');
            builder.append("- rankingBlocksByEra: ").append(rankingBlocksByEraSnapshot()).append('\n');
            builder.append("- firstRankingBlockNo: ").append(firstRankingBlockNo.get()).append('\n');
            builder.append("- lastRankingBlockNo: ").append(lastRankingBlockNo.get()).append('\n');
            builder.append("- firstRankingBlockSlot: ").append(firstRankingBlockSlot.get()).append('\n');
            builder.append("- lastRankingBlockSlot: ").append(lastRankingBlockSlot.get()).append('\n');
            builder.append("- rankingBlockTransactions: ").append(transactions.get()).append('\n');
            builder.append("- rankingBlockAnnouncements: ").append(rankingBlockAnnouncements.get()).append('\n');
            builder.append("- leiosCertifiedTrue: ").append(leiosCertifiedTrue.get()).append('\n');
            builder.append("- leiosCertifiedFalse: ").append(leiosCertifiedFalse.get()).append('\n');
            builder.append("- leiosCertifiedParentAnnouncementMatches: ")
                    .append(leiosCertifiedParentAnnouncementMatches.get()).append('\n');
            builder.append("- leiosCertifiedParentAnnouncementMismatches: ")
                    .append(leiosCertifiedParentAnnouncementMismatches.get()).append('\n');
            builder.append("- uniqueAnnouncedEbHashes: ").append(announcedEbHashes.size()).append('\n');
            builder.append("- endorserBlocks: ").append(endorserBlocks.get()).append('\n');
            builder.append("- uniqueEndorserBlockHashes: ").append(endorserBlockHashes.size()).append('\n');
            builder.append("- announcementEbCorrelation: ").append(correlatedEbCount()).append('\n');
            builder.append("- completeEndorserBlocks: ").append(completeEndorserBlocks.get()).append('\n');
            builder.append("- incompleteEndorserBlocks: ").append(incompleteEndorserBlocks.get()).append('\n');
            builder.append("- endorserBlockTxRefs: ").append(endorserBlockTxRefs.get()).append('\n');
            builder.append("- fetchedEndorserBlockTxs: ").append(endorserBlockTxs.get()).append('\n');
            builder.append("- parsedEndorserBlockTxs: ").append(parsedEndorserBlockTxs.get()).append('\n');
            builder.append("- unparsedEndorserBlockTxs: ").append(unparsedEndorserBlockTxs.get()).append('\n');
            builder.append("- endorserBlockHashMatches: ").append(endorserBlockHashMatches.get()).append('\n');
            builder.append("- endorserBlockHashMismatches: ").append(endorserBlockHashMismatches.get()).append('\n');
            builder.append("- txHashMembershipMatches: ").append(txHashMembershipMatches.get()).append('\n');
            builder.append("- txHashMembershipMismatches: ").append(txHashMembershipMismatches.get()).append('\n');
            builder.append("- voteBatches: ").append(voteBatches.get()).append('\n');
            builder.append("- votes: ").append(votes.get()).append('\n');
            builder.append("- unknownVotes: ").append(unknownVotes.get()).append('\n');
            builder.append("- votesByFormat: ").append(counterMapSnapshot(votesByFormat)).append('\n');
            builder.append("- rollbacks: ").append(rollbacks.get()).append('\n');
            builder.append("- disconnects: ").append(disconnects.get()).append('\n');
            builder.append("- parseErrors: ").append(parseErrors.get()).append('\n');
            builder.append("- keepAlivesSent: ").append(keepAlivesSent.get()).append("\n\n");
        }

        private Map<Era, Integer> rankingBlocksByEraSnapshot() {
            Map<Era, Integer> snapshot = new EnumMap<>(Era.class);
            rankingBlocksByEra.forEach((era, count) -> snapshot.put(era, count.get()));
            return snapshot;
        }

        private Map<String, Integer> counterMapSnapshot(Map<String, AtomicInteger> counters) {
            Map<String, Integer> snapshot = new ConcurrentHashMap<>();
            counters.forEach((key, count) -> snapshot.put(key, count.get()));
            return snapshot;
        }

        private long correlatedEbCount() {
            return endorserBlockHashes.stream().filter(announcedEbHashes::contains).count();
        }

        private long durationSeconds() {
            if (startedAt.get() == null || completedAt.get() == null) {
                return -1;
            }
            return Duration.between(startedAt.get(), completedAt.get()).toSeconds();
        }

        private void appendSamples(StringBuilder builder, String title, Queue<String> samples) {
            builder.append("## ").append(title).append("\n\n");
            if (samples.isEmpty()) {
                builder.append("- none\n\n");
                return;
            }
            for (String sample : samples) {
                builder.append("- ").append(sample).append('\n');
            }
            builder.append('\n');
        }

        private String pointHash(LeiosPoint point) {
            return HexUtil.encodeHexString(point.getEbHash());
        }

        private record PreviousRankingBlock(long blockNumber, String blockHash, boolean hasAnnouncement) {
        }
    }

    private record E2EConfig(String host,
                             int port,
                             long durationSeconds,
                             long progressSeconds,
                             long noProgressTimeoutSeconds,
                             LeiosConfig.Mode blockSyncLeiosMode,
                             boolean startFromTip,
                             boolean fetchTxs,
                             int maxTxsPerEndorserBlock,
                             int txsOfferWaitMillis,
                             boolean deliverVotes,
                             int minRankingBlocks,
                             int minDijkstraRankingBlocks,
                             int minEndorserBlocks,
                             int minCompleteEndorserBlocks,
                             int sampleLimit,
                             Point intersectPoint,
                             Path reportPath) {

        /**
         * Builds the test configuration from environment variables. The default assertions
         * require live Dijkstra ranking blocks and zero parse errors. EB and vote traffic are
         * reported but opt-in as assertions because the public relay can have quiet windows with
         * no Leios announcements.
         */
        private static E2EConfig fromEnv() {
            String defaultReport = "build/reports/musashi-e2e/musashi-e2e-report.md";
            return new E2EConfig(
                    env("YACI_MUSASHI_HOST", DEFAULT_HOST),
                    intEnv("YACI_MUSASHI_PORT", DEFAULT_PORT),
                    longEnv("YACI_MUSASHI_E2E_DURATION_SECONDS", DEFAULT_DURATION_SECONDS),
                    longEnv("YACI_MUSASHI_E2E_PROGRESS_SECONDS", DEFAULT_PROGRESS_SECONDS),
                    longEnv("YACI_MUSASHI_E2E_NO_PROGRESS_TIMEOUT_SECONDS",
                            DEFAULT_NO_PROGRESS_TIMEOUT_SECONDS),
                    modeEnv("YACI_MUSASHI_E2E_BLOCKSYNC_LEIOS_MODE", LeiosConfig.Mode.AUTO),
                    booleanEnv("YACI_MUSASHI_E2E_START_FROM_TIP", false),
                    booleanEnv("YACI_MUSASHI_E2E_FETCH_TXS", true),
                    intEnv("YACI_MUSASHI_E2E_MAX_TXS_PER_EB", DEFAULT_MAX_TXS_PER_ENDORSER_BLOCK),
                    intEnv("YACI_MUSASHI_E2E_TXS_OFFER_WAIT_MILLIS", DEFAULT_TXS_OFFER_WAIT_MILLIS),
                    booleanEnv("YACI_MUSASHI_E2E_DELIVER_VOTES", true),
                    intEnv("YACI_MUSASHI_E2E_MIN_RB", DEFAULT_MIN_RANKING_BLOCKS),
                    intEnv("YACI_MUSASHI_E2E_MIN_DIJKSTRA_RB", DEFAULT_MIN_DIJKSTRA_RANKING_BLOCKS),
                    intEnv("YACI_MUSASHI_E2E_MIN_EB", DEFAULT_MIN_ENDORSER_BLOCKS),
                    intEnv("YACI_MUSASHI_E2E_MIN_COMPLETE_EB", DEFAULT_MIN_COMPLETE_ENDORSER_BLOCKS),
                    intEnv("YACI_MUSASHI_E2E_SAMPLE_LIMIT", DEFAULT_SAMPLE_LIMIT),
                    intersectPointFromEnv(),
                    Path.of(env("YACI_MUSASHI_E2E_REPORT", defaultReport)));
        }

        /**
         * The public Musashi relay rejects origin-based ChainSync on the current testnet.
         * The default point is the one pinned by the Musashi peer snapshot; callers can
         * override it when the testnet is reset.
         */
        private static Point intersectPointFromEnv() {
            String slot = System.getenv("YACI_MUSASHI_E2E_INTERSECT_SLOT");
            String hash = System.getenv("YACI_MUSASHI_E2E_INTERSECT_HASH");
            if ((slot == null || slot.isBlank()) && (hash == null || hash.isBlank())) {
                return DEFAULT_INTERSECT_POINT;
            }
            if (slot == null || slot.isBlank() || hash == null || hash.isBlank()) {
                throw new IllegalArgumentException("Both YACI_MUSASHI_E2E_INTERSECT_SLOT and "
                        + "YACI_MUSASHI_E2E_INTERSECT_HASH must be set together");
            }
            return new Point(Long.parseLong(slot), hash);
        }
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static int intEnv(String name, int defaultValue) {
        return Integer.parseInt(env(name, Integer.toString(defaultValue)));
    }

    private static long longEnv(String name, long defaultValue) {
        return Long.parseLong(env(name, Long.toString(defaultValue)));
    }

    private static boolean booleanEnv(String name, boolean defaultValue) {
        return Boolean.parseBoolean(env(name, Boolean.toString(defaultValue)));
    }

    private static LeiosConfig.Mode modeEnv(String name, LeiosConfig.Mode defaultValue) {
        return LeiosConfig.Mode.valueOf(env(name, defaultValue.name()).trim().toUpperCase());
    }
}
