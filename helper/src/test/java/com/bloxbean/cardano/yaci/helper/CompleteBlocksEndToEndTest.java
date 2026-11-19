package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.helper.model.Transaction;
import com.bloxbean.cardano.yaci.helper.model.leios.EndorserBlockUnavailableEvent.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.bloxbean.cardano.yaci.helper.EndorserBlockResolverTest.assertEventLoopTerminates;
import static com.bloxbean.cardano.yaci.helper.EndorserBlockResolverTest.waitUntil;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link BlockRangeSync} end to end against a local fake peer that serves the Musashi w42 fixtures over BlockFetch,
 * ChainSync and LeiosFetch: block 10027 reaches the listener with the 237 transactions of EB f951f865…, in db-sync's
 * order.
 */
class CompleteBlocksEndToEndTest {
    private FakeN2NPeer peer;
    private BlockRangeSync sync;
    private Point p10026;
    private Point p10027;
    private Point p10428;

    @BeforeEach
    void setUp() throws Exception {
        peer = new FakeN2NPeer();
        peer.blockReply = FakeN2NPeer.fixture("msg-leios-block-f951f865.hex");
        peer.txsReply = FakeN2NPeer.fixture("msg-leios-block-txs-f951f865.hex");
        peer.serve(FakeN2NPeer.fixture("block-10026.hex"))
                .serve(FakeN2NPeer.fixture("block-10027.hex"))
                .serve(FakeN2NPeer.fixture("block-10428.hex"));
        p10026 = point("block-10026.hex");
        p10027 = point("block-10027.hex");
        p10428 = point("block-10428.hex");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (sync != null)
            sync.stop(); //always: a halted or disconnected client may still reconnect
        peer.close();
    }

    @Test
    void aRangeStartingOnACertifyingBlockDeliversItCompleteWithoutItsParent() throws Exception {
        sync = new BlockRangeSync("127.0.0.1", peer.port(), FakeN2NPeer.MAGIC);
        CompleteBlockAssemblerTest.Recorder listener = recorder();
        sync.start(listener);

        sync.fetch(p10027, p10027);
        waitForBatchDone(listener, 1);

        assertThat(listener.blocks).hasSize(1); //the parent, fetched to find the EB, never reaches the listener
        Block complete = listener.blocks.get(0);
        assertThat(complete.getHeader().getHeaderBody().getBlockNumber()).isEqualTo(10027);
        assertThat(complete.getTransactionBodies()).extracting(TransactionBody::getTxHash)
                .containsExactlyElementsOf(FakeN2NPeer.dbSyncIds());
        assertThat(listener.transactions.get(0)).extracting(Transaction::getTxHash)
                .containsExactlyElementsOf(FakeN2NPeer.dbSyncIds());
        assertThat(complete.getLeiosEndorserBlock().getEbHash()).isEqualTo(EndorserBlockResolverTest.EB);
        assertThat(complete.getLeiosEndorserBlock().getEbSlot()).isEqualTo(p10026.getSlot());
        assertThat(complete.getLeiosEndorserBlock().getTxCount()).isEqualTo(237);
        assertThat(listener.events).isEmpty();

        //stop closes the main and the idle side connection, and the side connection's event loop
        EndorserBlockResolver.Side side = ((EndorserBlockResolver) control(sync).current().source).currentSide();
        assertThat(side).isNotNull();
        sync.stop();
        waitUntil(() -> peer.openConnections.get() == 0, 10_000);
        assertEventLoopTerminates(side.client);
    }

    @Test
    void aCertifyingBlockAfterItsParentUsesTheCachedAnnouncement() throws Exception {
        //10026 certifies too, and its parent is not served: skip mode delivers it as received after its budget
        peer.intersectNotFound = true; //no parent can be found on the peer: only the cache can complete 10027
        sync = new BlockRangeSync("127.0.0.1", peer.port(), FakeN2NPeer.MAGIC,
                LeiosConfig.builder().skipUnavailableEndorserBlocks(true).build(), 1_500, 200);
        CompleteBlockAssemblerTest.Recorder listener = recorder();
        sync.start(listener);

        sync.fetch(p10026, p10026);
        waitForBatchDone(listener, 1);
        assertThat(listener.blocks.get(0).getTransactionBodies()).isEmpty();
        assertThat(listener.outcomes()).last().isEqualTo(Outcome.DELIVERED_WITHOUT_EB_TXS);

        sync.fetch(p10027, p10027);
        waitForBatchDone(listener, 2);

        assertThat(listener.blocks).hasSize(2);
        assertThat(listener.blocks.get(1).getTransactionBodies()).hasSize(237);
        assertThat(listener.events).allMatch(e -> e.getCertifyingBlockNumber() == 10026);
    }

    @Test
    void aFetchFromInsideBatchDoneWorksAsOnNext() throws Exception {
        sync = new BlockRangeSync("127.0.0.1", peer.port(), FakeN2NPeer.MAGIC); //the default LeiosConfig
        CompleteBlockAssemblerTest.Recorder listener = recorder();
        java.util.concurrent.atomic.AtomicReference<Throwable> failed = new java.util.concurrent.atomic.AtomicReference<>();
        AtomicBoolean fetchedAgain = new AtomicBoolean();
        listener.onBatchDone = () -> {
            if (fetchedAgain.compareAndSet(false, true)) {
                try {
                    sync.fetch(p10428, p10428); //the RequestRange goes out before the agent clears its cursor
                } catch (Throwable e) {
                    failed.set(e);
                }
            }
        };
        sync.start(listener);

        sync.fetch(p10428, p10428);
        waitForBatchDone(listener, 2);

        assertThat(failed.get()).isNull();
        assertThat(listener.blocks).hasSize(2);
    }

    @Test
    void aParentSearchThatGetsNoAnswerIsRetriedAndTheBlockArrivesWithinTheBudget() throws Exception {
        peer.silentIntersectConnections = 1; //the first parent lookup's FindIntersect is never answered
        sync = new BlockRangeSync("127.0.0.1", peer.port(), FakeN2NPeer.MAGIC);
        CompleteBlockAssemblerTest.Recorder listener = recorder();
        sync.start(listener);

        sync.fetch(p10027, p10027);
        waitForBatchDone(listener, 1, 40);

        assertThat(listener.outcomes()).containsExactly(Outcome.RETRYING);
        assertThat(listener.blocks).hasSize(1);
        assertThat(listener.blocks.get(0).getTransactionBodies()).hasSize(237);
    }

    @Test
    void aSideConnectionThatNeverAnswersIsReplacedAndTheBlockArrivesWithinTheBudget() throws Exception {
        peer.silentLeiosFetchConnections = 1; //the first side connection's request is never answered
        sync = new BlockRangeSync("127.0.0.1", peer.port(), FakeN2NPeer.MAGIC);
        CompleteBlockAssemblerTest.Recorder listener = recorder();
        sync.start(listener);

        long start = System.nanoTime();
        sync.fetch(p10027, p10027);
        waitForBatchDone(listener, 1, 40);

        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
        assertThat(seconds).isLessThan(CompleteBlockAssembler.BUDGET_MILLIS / 1_000);
        assertThat(listener.outcomes()).containsExactly(Outcome.RETRYING);
        assertThat(listener.blocks).hasSize(1);
        assertThat(listener.blocks.get(0).getTransactionBodies()).hasSize(237);
    }

    @Test
    void aFetchWhileARangeIsHeldOnACertifyingBlockIsRejectedAndTheRangeCompletes() throws Exception {
        sync = new BlockRangeSync("127.0.0.1", peer.port(), FakeN2NPeer.MAGIC);
        CompleteBlockAssemblerTest.Recorder listener = recorder();
        sync.start(listener);
        CountDownLatch hold = new CountDownLatch(1);
        peer.holdLeiosFetch = hold;

        sync.fetch(p10027, p10428); //10428 and BatchDone are sent behind 10027 at once
        waitUntil(() -> peer.received(19).stream().anyMatch(r -> r.tag() == 1), 10_000);

        assertThatThrownBy(() -> sync.fetch(p10428, p10428)).isInstanceOf(IllegalStateException.class);

        hold.countDown();
        waitForBatchDone(listener, 1);
        assertThat(listener.blocks).extracting(b -> b.getHeader().getHeaderBody().getBlockNumber())
                .containsExactly(10027L, 10428L);
        assertThat(listener.blocks.get(0).getTransactionBodies()).hasSize(237);

        sync.fetch(p10428, p10428); //no certifying block waits any more
        waitForBatchDone(listener, 2);
        assertThat(listener.blocks).hasSize(3);
    }

    @Test
    void aHaltedRangeRejectsFetchAndResumesWithRestartThenFetch() throws Exception {
        peer.fetch = FakeN2NPeer.Fetch.CLOSE;
        sync = new BlockRangeSync("127.0.0.1", peer.port(), FakeN2NPeer.MAGIC, LeiosConfig.defaultConfig(),
                1_000, 200);
        CompleteBlockAssemblerTest.Recorder first = recorder();
        sync.start(first);

        sync.fetch(p10027, p10428);
        waitUntil(() -> first.outcomes().contains(Outcome.SYNC_HALTED), 10_000);

        assertThat(sync.isRunning()).isFalse();
        assertThatThrownBy(() -> sync.fetch(p10027, p10027)).isInstanceOf(IllegalStateException.class);
        Thread.sleep(300);
        assertThat(first.blocks).isEmpty(); //neither the halted block nor its successor
        assertThat(first.calls).doesNotContain("batchDone");

        peer.fetch = FakeN2NPeer.Fetch.ANSWER;
        CompleteBlockAssemblerTest.Recorder second = recorder();
        sync.restart(second);
        sync.fetch(p10027, p10428);
        waitForBatchDone(second, 1);
        assertThat(second.blocks).extracting(b -> b.getTransactionBodies().size()).containsExactly(237,
                listenerTxCount("block-10428.hex"));
        assertThat(sync.isRunning()).isTrue();
    }

    private static int listenerTxCount(String fixture) throws Exception {
        return BlockSerializer.INSTANCE.deserialize(FakeN2NPeer.fixture(fixture)).getTransactionBodies().size();
    }

    private static CompleteBlockAssemblerTest.Recorder recorder() {
        return new CompleteBlockAssemblerTest.Recorder();
    }

    private static void waitForBatchDone(CompleteBlockAssemblerTest.Recorder listener, int count)
            throws InterruptedException {
        waitForBatchDone(listener, count, 20);
    }

    private static void waitForBatchDone(CompleteBlockAssemblerTest.Recorder listener, int count, int seconds)
            throws InterruptedException {
        long end = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds);
        while (listener.calls.stream().filter("batchDone"::equals).count() < count && System.currentTimeMillis() < end)
            Thread.sleep(20);
        assertThat(listener.calls.stream().filter("batchDone"::equals).count()).isEqualTo(count);
    }

    private static Point point(String fixture) throws Exception {
        HeaderBody header = BlockSerializer.INSTANCE.deserialize(FakeN2NPeer.fixture(fixture)).getHeader()
                .getHeaderBody();
        return new Point(header.getSlot(), header.getBlockHash());
    }

    @SuppressWarnings("unchecked")
    private static SyncControl<BlockFetcher> control(BlockRangeSync sync) throws Exception {
        Field control = BlockRangeSync.class.getDeclaredField("control");
        control.setAccessible(true);
        return (SyncControl<BlockFetcher>) control.get(sync);
    }
}
