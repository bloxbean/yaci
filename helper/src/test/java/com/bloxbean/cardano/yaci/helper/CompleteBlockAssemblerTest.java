package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.exception.BlockParseRuntimeException;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.model.LeiosEndorserBlockRef;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgent;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.messages.MsgBlock;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.LeiosFetchStateBase;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosBlockTxs;
import com.bloxbean.cardano.yaci.helper.api.Fetcher;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.helper.listener.BlockFetchAgentListenerAdapter;
import com.bloxbean.cardano.yaci.helper.listener.ChainSyncListenerAdapter;
import com.bloxbean.cardano.yaci.helper.model.Transaction;
import com.bloxbean.cardano.yaci.helper.model.leios.EndorserBlockUnavailableEvent;
import com.bloxbean.cardano.yaci.helper.model.leios.EndorserBlockUnavailableEvent.Outcome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.bloxbean.cardano.yaci.helper.EndorserBlockResolverTest.waitUntil;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR 0016 D3b and D3c with fake main clients and a fake endorser block source: the delegate of each start, and the
 * ownership rules of {@link SyncControl}. The main connection's thread is modelled by a thread holding the fake
 * client's agent lock, which its shutdown also takes (as {@code Session.dispose} takes the agent's monitor).
 * <p>
 * BlockSync's known-parent path against a real peer (tip sync from an intersection) is covered by the live Musashi
 * tip sync; here {@link #aTipSyncPassesItsIntersectionAsTheKnownParent} checks the delegate's part.
 */
class CompleteBlockAssemblerTest {
    private static byte[] b10027Bytes;
    private static Block b9091;
    private static Block b10026;
    private static Block b10027;
    private static List<byte[]> ebTransactions;
    private static List<String> dbSyncIds;

    private final EbAnnouncementCache cache = new EbAnnouncementCache();
    private final FakeSource source = new FakeSource();

    @BeforeAll
    static void fixtures() throws Exception {
        b10027Bytes = FakeN2NPeer.fixture("block-10027.hex");
        b9091 = BlockSerializer.INSTANCE.deserialize(FakeN2NPeer.fixture("block-9091.hex"));
        b10026 = BlockSerializer.INSTANCE.deserialize(FakeN2NPeer.fixture("block-10026.hex"));
        b10027 = BlockSerializer.INSTANCE.deserialize(b10027Bytes);
        ebTransactions = ((MsgLeiosBlockTxs) LeiosFetchStateBase.deserialize(
                FakeN2NPeer.fixture("msg-leios-block-txs-f951f865.hex"))).getTransactions();
        dbSyncIds = FakeN2NPeer.dbSyncIds();
    }

    // ---- I1: blocks without a certificate

    @Test
    void aBlockWithoutACertificateIsForwardedAsItIsOnTheCallingThread() {
        Recorder recorder = new Recorder();
        FakeFetcher client = start(control(false, 1_000, 100), recorder);
        List<Transaction> own = new ArrayList<>();

        client.wired.onBlock(Era.Dijkstra, b9091, own);
        client.blockFetch.blockFound(b9091);

        assertThat(recorder.blocks).hasSize(2);
        assertThat(recorder.blocks.get(0)).isSameAs(b9091);
        assertThat(recorder.transactions.get(0)).isSameAs(own);
        assertThat(recorder.threads).containsOnly(Thread.currentThread());
        assertThat(source.calls.get()).isZero();

        recorder.throwOnBlock = new IllegalStateException("listener");
        assertThatThrownBy(() -> client.blockFetch.blockFound(b9091)).hasMessage("listener");
    }

    @Test
    void aListenerExceptionOnACompletedCertifyingBlockTakesBlockfetchAgentsOrdinaryPath() {
        warm();
        Recorder recorder = new Recorder();
        recorder.throwOnBlock = new IllegalStateException("listener");
        FakeFetcher client = start(control(false, 1_000, 100), recorder);
        BlockfetchAgent agent = new BlockfetchAgent();
        agent.addListener(client.blockFetch);

        agent.processResponse(new MsgBlock(b10027Bytes));

        assertThat(recorder.blocks).hasSize(1);
        assertThat(recorder.blocks.get(0).getTransactionBodies()).hasSize(237);
        assertThat(recorder.parsingErrors).hasSize(1);
        assertThat(recorder.parsingErrors.get(0).getBlockCbor()).isEqualTo(b10027Bytes);
        assertThat(recorder.events).isEmpty();
    }

    // ---- D3b: attempts within the budget

    @Test
    void aSuccessOnTheSecondAttemptDeliversTheCompleteBlockOnceAfterOneRetrying() {
        warm();
        source.script(10027, "fail", "ok");
        Recorder recorder = new Recorder();
        recorder.throwOnUnavailable = true; //changes nothing
        FakeFetcher client = start(control(false, 10_000, 50), recorder);

        client.blockFetch.blockFound(b10027);

        assertThat(recorder.outcomes()).containsExactly(Outcome.RETRYING);
        assertThat(recorder.events.get(0).getEbHash()).isEqualTo(EndorserBlockResolverTest.EB);
        assertThat(recorder.events.get(0).getAttempt()).isEqualTo(1);
        assertThat(recorder.blocks).hasSize(1);
        Block complete = recorder.blocks.get(0);
        assertThat(complete.getTransactionBodies()).extracting(TransactionBody::getTxHash)
                .containsExactlyElementsOf(dbSyncIds);
        assertThat(recorder.transactions.get(0)).extracting(Transaction::getTxHash).containsExactlyElementsOf(dbSyncIds);
        assertThat(complete.getLeiosEndorserBlock().getTxCount()).isEqualTo(237);
        assertThat(recorder.threads).containsOnly(Thread.currentThread());
        assertThat(source.threads).containsOnly(Thread.currentThread());
    }

    @Test
    void aTipSyncPassesItsIntersectionAsTheKnownParent() {
        Recorder recorder = new Recorder();
        FakeFetcher client = start(control(false, 1_000, 100), recorder);
        HeaderBody parent = b10026.getHeader().getHeaderBody();
        Point intersection = new Point(parent.getSlot(), parent.getBlockHash());

        client.chainSync.intersactFound(new Tip(intersection, 10026), intersection);
        client.blockFetch.blockFound(b10027);

        assertThat(source.knownParents).containsExactly(intersection);
        assertThat(recorder.calls).containsExactly("intersactFound", "onBlock");
    }

    @Test
    void theBudgetRunningOutDuringAPauseGivesExactlyOneTerminalOutcomeAndNoNewAttempt() {
        source.fallback = "fail";
        Recorder recorder = new Recorder();
        FakeFetcher client = start(control(false, 1_000, 5_000), recorder);

        long start = System.nanoTime();
        client.blockFetch.blockFound(b10027);

        assertThat(recorder.outcomes()).containsExactly(Outcome.RETRYING, Outcome.SYNC_HALTED);
        assertThat(source.calls.get()).isEqualTo(1);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isBetween(900L, 4_000L);
    }

    @Test
    void aSuccessThatComesAfterTheBudgetIsNotDelivered() {
        warm();
        source.fallback = "late";
        Recorder recorder = new Recorder();
        FakeFetcher client = start(control(true, 300, 50), recorder);

        client.blockFetch.blockFound(b10027);

        assertThat(recorder.outcomes()).containsExactly(Outcome.DELIVERED_WITHOUT_EB_TXS);
        assertThat(recorder.blocks).containsExactly(b10027); //as received
    }

    @Test
    void anInterruptIsAnExhaustedBudget() {
        source.fallback = "interrupt";
        Recorder recorder = new Recorder();
        FakeFetcher client = start(control(false, 60_000, 50), recorder);

        try {
            client.blockFetch.blockFound(b10027);
        } finally {
            Thread.interrupted();
        }

        assertThat(recorder.outcomes()).containsExactly(Outcome.SYNC_HALTED);
        assertThat(recorder.events.get(0).getReason()).isEqualTo("interrupted");
    }

    // ---- D3c: strict halt and skip

    @Test
    void whenTheStrictBudgetRunsOutTheSyncHalts() throws Exception {
        source.fallback = "fail";
        Recorder recorder = new Recorder();
        SyncControl<FakeFetcher> control = control(false, 300, 100);
        FakeFetcher client = start(control, recorder);

        client.blockFetch.blockFound(b10027);

        assertThat(control.isRunning()).isFalse(); //at once, before the halting thread runs
        List<Outcome> outcomes = recorder.outcomes();
        assertThat(outcomes.get(outcomes.size() - 1)).isEqualTo(Outcome.SYNC_HALTED);
        assertThat(outcomes.subList(0, outcomes.size() - 1)).containsOnly(Outcome.RETRYING);
        assertThat(recorder.blocks).isEmpty();
        waitUntil(() -> client.shutdowns.get() >= 1, 5_000);
        assertThat(client.isRunning()).isFalse();
        waitUntil(() -> source.closes.get() >= 1, 5_000); //the halted start's side connection
    }

    @Test
    void afterAHaltBothAdaptersDropEverythingAndAFreshStartForwardsAgain() {
        source.fallback = "fail";
        Recorder recorder = new Recorder();
        SyncControl<FakeFetcher> control = control(false, 200, 50);
        FakeFetcher halted = start(control, recorder);
        halted.blockFetch.blockFound(b10027);
        int eventsAtHalt = recorder.events.size();

        everyCallback(halted);
        assertThat(recorder.calls).isEmpty(); //both disconnects included
        assertThat(recorder.events).hasSize(eventsAtHalt);

        Recorder fresh = new Recorder();
        FakeFetcher restarted = start(control, fresh);
        everyCallback(restarted);
        assertThat(fresh.calls).containsExactlyInAnyOrderElementsOf(EVERY_CALLBACK);
        assertThat(control.isRunning()).isTrue();
    }

    @Test
    void aRestartAfterAHaltDeliversTheCompleteBlockWithTheCacheWarm() {
        source.script(10026, "ok");
        source.script(10027, "fail", "fail", "fail", "fail", "fail", "fail", "fail", "fail", "fail", "fail");
        Recorder first = new Recorder();
        SyncControl<FakeFetcher> control = control(false, 200, 50);
        FakeFetcher client = start(control, first);
        client.blockFetch.blockFound(b10026); //delivered: its announcement is cached
        client.blockFetch.blockFound(b10027); //halts
        assertThat(first.blocks).hasSize(1);
        assertThat(first.outcomes()).contains(Outcome.SYNC_HALTED);

        source.script(10027, "ok");
        source.requireWarm = true;
        Recorder second = new Recorder();
        FakeFetcher restarted = start(control, second);
        restarted.blockFetch.blockFound(b10027);

        assertThat(second.blocks).hasSize(1);
        assertThat(second.blocks.get(0).getTransactionBodies()).hasSize(237);
        assertThat(second.events).isEmpty();
    }

    @Test
    void aHaltingThreadThatRunsAfterANewerStartLeavesItRunning() throws Exception {
        source.fallback = "fail";
        List<Runnable> haltTasks = new CopyOnWriteArrayList<>();
        SyncControl<FakeFetcher> control = new SyncControl<>(LeiosConfig.defaultConfig(), cache, owner -> source,
                200, 50, haltTasks::add, 0);
        FakeFetcher old = start(control, new Recorder());

        //the old connection's thread halts and stays inside its agent's monitor (receiveResponse)
        CountDownLatch haltedLatch = new CountDownLatch(1);
        CountDownLatch leave = new CountDownLatch(1);
        Thread loop = new Thread(() -> {
            synchronized (old.agentLock) {
                old.blockFetch.blockFound(b10027);
                haltedLatch.countDown();
                await(leave);
            }
        });
        loop.start();
        assertThat(haltedLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(haltTasks).hasSize(1);

        //a newer start: published at once, then blocked retiring the old client until the old loop leaves
        AtomicReference<FakeFetcher> newer = new AtomicReference<>();
        Thread starter = new Thread(() -> newer.set(start(control, new Recorder())));
        starter.start();
        waitUntil(() -> control.client() != old, 5_000);

        //the halting thread runs now; it never waits for the old loop, and leaves the newer start alone
        Thread halting = new Thread(haltTasks.get(0));
        halting.start();
        halting.join(2_000);
        assertThat(halting.isAlive()).isFalse();

        leave.countDown();
        starter.join(5_000);
        loop.join(5_000);
        assertThat(newer.get().isRunning()).isTrue();
        assertThat(newer.get().shutdowns.get()).isZero();
        assertThat(old.shutdowns.get()).isEqualTo(1);
        assertThat(control.isRunning()).isTrue();
    }

    @Test
    void skipModeDeliversTheOriginalBlockWithItsOwnTransactionsOnce() {
        source.fallback = "fail";
        Recorder recorder = new Recorder();
        SyncControl<FakeFetcher> control = control(true, 300, 100);
        FakeFetcher client = start(control, recorder);
        List<Transaction> own = new ArrayList<>();

        client.wired.onBlock(Era.Dijkstra, b10027, own);

        assertThat(recorder.outcomes().stream().filter(o -> o != Outcome.RETRYING))
                .containsExactly(Outcome.DELIVERED_WITHOUT_EB_TXS);
        assertThat(recorder.blocks).hasSize(1);
        assertThat(recorder.blocks.get(0)).isSameAs(b10027);
        assertThat(recorder.transactions.get(0)).isSameAs(own);
        assertThat(control.isRunning()).isTrue();
    }

    // ---- D: stop, restart and startSync

    @Test
    void aStoppedRangeSyncStillForwardsItsDisconnect() {
        Recorder recorder = new Recorder();
        SyncControl<FakeFetcher> control = control(false, 1_000, 100);
        FakeFetcher client = start(control, recorder);

        stop(control);
        client.blockFetch.onDisconnect(); //BlockFetcher's one route

        assertThat(recorder.calls).containsExactly("onDisconnect");
    }

    @Test
    void aStoppedTipSyncStillForwardsBothDisconnects() {
        Recorder recorder = new Recorder();
        SyncControl<FakeFetcher> control = control(false, 1_000, 100);
        FakeFetcher client = start(control, recorder);

        stop(control);
        client.chainSync.onDisconnect();
        client.blockFetch.onDisconnect();

        assertThat(recorder.calls).containsExactly("onDisconnect", "onDisconnect");
    }

    @Test
    void aHaltedSyncForwardsNoDisconnect() {
        source.fallback = "fail";
        Recorder recorder = new Recorder();
        SyncControl<FakeFetcher> control = control(false, 200, 50);
        FakeFetcher client = start(control, recorder);
        client.blockFetch.blockFound(b10027);

        client.chainSync.onDisconnect();
        client.blockFetch.onDisconnect();

        assertThat(recorder.calls).isEmpty();
    }

    @Test
    void stopDuringAnAttemptReturnsWithinASecondAndDropsTheBufferedSuccessor() throws Exception {
        warm();
        source.fallback = "hang";
        Recorder recorder = new Recorder();
        SyncControl<FakeFetcher> control = control(false, 10_000, 1_000);
        FakeFetcher client = start(control, recorder);

        Thread loop = loop(client, b10027, b9091); //the successor is already buffered
        waitUntil(() -> source.calls.get() == 1, 5_000);
        Thread.sleep(100);

        long start = System.nanoTime();
        stop(control); //from another thread
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        loop.join(2_000);

        assertThat(millis).isLessThan(1_000);
        assertThat(loop.isAlive()).isFalse();
        assertThat(recorder.blocks).isEmpty();
        assertThat(recorder.events).isEmpty();
        assertThat(client.isRunning()).isFalse();
    }

    @Test
    void stopDuringAPauseReturnsWithinASecond() throws Exception {
        source.fallback = "fail";
        Recorder recorder = new Recorder();
        SyncControl<FakeFetcher> control = control(false, 20_000, 10_000);
        FakeFetcher client = start(control, recorder);

        Thread loop = loop(client, b10027, b9091);
        waitUntil(() -> recorder.events.size() == 1, 5_000);

        long start = System.nanoTime();
        stop(control);
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        loop.join(2_000);

        assertThat(millis).isLessThan(1_000);
        assertThat(recorder.outcomes()).containsExactly(Outcome.RETRYING);
        assertThat(recorder.blocks).isEmpty();
        assertThat(source.calls.get()).isEqualTo(1);
    }

    @Test
    void aStopJustBeforeTheSkipDeliveryIsCommittedWins() throws Exception {
        source.fallback = "fail";
        Recorder recorder = new Recorder();
        SyncControl<FakeFetcher> control = control(true, 300, 50);
        FakeFetcher client = start(control, recorder);
        //the attempt that exhausts the budget retires the start before it fails, from another thread
        source.beforeLastFailure = deadline -> {
            CountDownLatch retired = new CountDownLatch(1);
            new Thread(() -> {
                SyncControl.Start<FakeFetcher> stopped = control.retireCurrent();
                retired.countDown();
                control.retire(stopped);
            }).start();
            await(retired);
        };

        Thread loop = loop(client, b10027, b9091);
        loop.join(5_000);

        assertThat(recorder.outcomes()).doesNotContain(Outcome.DELIVERED_WITHOUT_EB_TXS, Outcome.SYNC_HALTED);
        assertThat(recorder.blocks).isEmpty(); //neither the block nor its successor
    }

    @Test
    void aSkipDeliveryAlreadyCommittedFinishesWhenTheStartIsRetiredDuringItsReport() throws Exception {
        source.fallback = "fail";
        Recorder recorder = new Recorder();
        SyncControl<FakeFetcher> control = control(true, 300, 100);
        FakeFetcher client = start(control, recorder);
        CountDownLatch retired = new CountDownLatch(1);
        recorder.onUnavailable = event -> {
            if (event.getOutcome() == Outcome.DELIVERED_WITHOUT_EB_TXS) {
                //stop() from another thread: it retires the start at once, then waits for this thread to leave
                new Thread(() -> {
                    SyncControl.Start<FakeFetcher> stopped = control.retireCurrent();
                    retired.countDown();
                    control.retire(stopped);
                }).start();
                await(retired);
            }
        };

        Thread loop = loop(client, b10027, b9091);
        loop.join(5_000);

        //committed before the stop: delivered; the successor is still forwarded (no call was cancelled), as on next
        assertThat(recorder.blocks).containsExactly(b10027, b9091);
        waitUntil(() -> client.shutdowns.get() >= 1, 5_000);
    }

    @Test
    void aStartRetiredBeforeItsClientStartsNeverStartsIt() {
        SyncControl<FakeFetcher> control = control(false, 1_000, 100);
        SyncControl.Swap<FakeFetcher> swap = control.replace(new Recorder(), FakeFetcher::new);
        //the starting caller pauses after publishing; stop() completes meanwhile
        stop(control);

        control.start(swap.created()); //the caller resumes

        assertThat(swap.created().client.starts.get()).isZero();
        assertThat(swap.created().client.isRunning()).isFalse();
    }

    @Test
    void aStartRetiredWhileItsClientStartsIsShutDownAgain() {
        SyncControl<FakeFetcher> control = control(false, 1_000, 100);
        SyncControl.Swap<FakeFetcher> swap = control.replace(new Recorder(), FakeFetcher::new);
        swap.created().client.duringStart = () -> {
            Thread stopper = new Thread(() -> stop(control));
            stopper.start();
            try {
                stopper.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        control.start(swap.created());

        assertThat(swap.created().client.starts.get()).isEqualTo(1);
        assertThat(swap.created().client.isRunning()).isFalse(); //the old transport stays down
        assertThat(swap.created().client.shutdowns.get()).isGreaterThanOrEqualTo(2);
    }

    // ---- round 2

    @Test
    void aReplacedStartForwardsOnlyDisconnects() {
        Recorder old = new Recorder();
        SyncControl<FakeFetcher> control = control(false, 1_000, 100);
        FakeFetcher replacedClient = start(control, old);
        start(control, new Recorder()); //startSync again: the old start is replaced

        replacedClient.blockFetch.blockFound(b9091); //a reconnect of the replaced client delivers a block
        replacedClient.chainSync.rollbackward(new Tip(new Point(1, "aa"), 1), new Point(1, "aa"));
        replacedClient.chainSync.onDisconnect(); //tip sync: both adapters report the disconnect, as on next
        replacedClient.blockFetch.onDisconnect();

        assertThat(old.blocks).isEmpty();
        assertThat(old.calls).containsExactly("onDisconnect", "onDisconnect");
    }
    @Test
    void isRunningIsAlreadyFalseWhenSyncHaltedIsReported() {
        source.fallback = "fail";
        Recorder recorder = new Recorder();
        SyncControl<FakeFetcher> control = control(false, 200, 50);
        FakeFetcher client = start(control, recorder);
        List<Boolean> runningAtHalt = new CopyOnWriteArrayList<>();
        recorder.onUnavailable = event -> {
            if (event.getOutcome() == Outcome.SYNC_HALTED)
                runningAtHalt.add(control.isRunning());
        };

        client.blockFetch.blockFound(b10027);

        assertThat(runningAtHalt).containsExactly(false);
    }

    @Test
    void aCancellationThatIsNotOursIsAnOrdinaryFailedAttempt() {
        warm();
        source.script(10027, "foreign-cancel", "ok");
        Recorder recorder = new Recorder();
        FakeFetcher client = start(control(false, 10_000, 50), recorder);

        client.blockFetch.blockFound(b10027);

        assertThat(recorder.outcomes()).containsExactly(Outcome.RETRYING);
        assertThat(recorder.blocks).hasSize(1);
        assertThat(recorder.blocks.get(0).getTransactionBodies()).hasSize(237);
    }

    @Test
    void theSkipDeliveryAfterAnInterruptRunsWithoutTheInterruptFlagWhichIsRestored() {
        source.fallback = "interrupt";
        Recorder recorder = new Recorder();
        List<Boolean> interruptedInListener = new CopyOnWriteArrayList<>();
        recorder.onBlockHook = () -> interruptedInListener.add(Thread.currentThread().isInterrupted());
        FakeFetcher client = start(control(true, 60_000, 50), recorder);

        boolean restored;
        try {
            client.blockFetch.blockFound(b10027);
        } finally {
            restored = Thread.interrupted();
        }

        assertThat(recorder.outcomes()).containsExactly(Outcome.DELIVERED_WITHOUT_EB_TXS);
        assertThat(interruptedInListener).containsExactly(false);
        assertThat(restored).isTrue();
    }

    @Test
    void aStopBetweenASuccessfulAttemptAndItsCommitDeliversNothing() {
        warm();
        Recorder recorder = new Recorder();
        SyncControl<FakeFetcher> control = control(false, 10_000, 50);
        FakeFetcher client = start(control, recorder);
        source.afterSuccess = () -> control.retireCurrent(); //stop() lands after complete(), before the commit

        client.blockFetch.blockFound(b10027);
        client.blockFetch.blockFound(b9091);

        assertThat(recorder.blocks).isEmpty();
        assertThat(recorder.events).isEmpty();
    }

    @Test
    void aCertifyingBlockAfterAStopOpensNoSideConnection() {
        warm();
        Recorder recorder = new Recorder();
        SyncControl<FakeFetcher> control = control(false, 10_000, 50);
        FakeFetcher client = start(control, recorder);
        stop(control);

        client.blockFetch.blockFound(b10027);

        assertThat(source.calls.get()).isZero();
        assertThat(recorder.blocks).isEmpty();
        assertThat(recorder.events).isEmpty();
    }

    @Test
    void aRetiredClientThatComesBackIsShutDownAgainByTheRecheck() throws Exception {
        AtomicInteger shutdowns = new AtomicInteger();
        AtomicBoolean running = new AtomicBoolean(true);

        ClientRetirement.retire(new Object(), () -> {
            //the first shutdown races an automatic reconnect, which revives the client
            running.set(shutdowns.incrementAndGet() == 1);
        }, running::get, () -> false, new long[]{200, 400}, 1_000);

        waitUntil(() -> shutdowns.get() == 2 && !running.get(), 3_000);
    }

    @Test
    void aStuckShutdownDoesNotDelayAnotherClientsRecheck() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch stuck = new CountDownLatch(1);
        ClientRetirement.retireAsync(new Object(), () -> {
            stuck.countDown();
            await(release); //a side connection whose close hangs
        }, () -> false, () -> false);
        assertThat(stuck.await(5, TimeUnit.SECONDS)).isTrue();

        AtomicInteger shutdowns = new AtomicInteger();
        AtomicBoolean running = new AtomicBoolean(true);
        try {
            ClientRetirement.retire(new Object(), () -> running.set(shutdowns.incrementAndGet() == 1), running::get,
                    () -> false, new long[]{100, 200}, 1_000);

            waitUntil(() -> shutdowns.get() == 2 && !running.get(), 3_000);
        } finally {
            release.countDown();
        }
    }

    @Test
    void aClientWhoseStartHasNotReturnedIsCheckedUntilItReturns() throws Exception {
        AtomicInteger shutdowns = new AtomicInteger();
        AtomicBoolean running = new AtomicBoolean(false);
        AtomicBoolean starting = new AtomicBoolean(true);

        ClientRetirement.retire(new Object(), () -> {
            shutdowns.incrementAndGet();
            running.set(false);
        }, running::get, starting::get, new long[]{50, 100}, 200);
        Thread.sleep(300); //both rechecks passed while it was not running
        running.set(true); //its start resumes late and connects

        waitUntil(() -> shutdowns.get() == 2, 3_000);
        starting.set(false); //its start returned: the starter's own check takes over
        Thread.sleep(400); //a recheck already scheduled runs once more, then they stop
        int settled = shutdowns.get();
        running.set(true);
        Thread.sleep(600);
        assertThat(shutdowns.get()).isEqualTo(settled);
    }
    @Test
    void shutdownsOfOneClientNeverOverlapHoldNoLockAndAFailureIsNotThrown() throws Exception {
        Object client = new Object();
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger overlaps = new AtomicInteger();
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Runnable shutdown = () -> {
            runs.incrementAndGet();
            if (inside.incrementAndGet() > 1)
                overlaps.incrementAndGet();
            entered.countDown();
            await(release);
            inside.decrementAndGet();
            throw new NullPointerException("session"); //as NodeClient.shutdown can when raced
        };
        Thread first = new Thread(() -> ClientRetirement.retire(client, shutdown, () -> false, () -> false));
        first.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        //a second caller (stop() on the event loop, say) finds a shutdown in progress and returns at once
        long start = System.nanoTime();
        ClientRetirement.retire(client, shutdown, () -> false, () -> false);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(500);

        release.countDown();
        first.join(5_000);
        assertThat(overlaps.get()).isZero();
        assertThat(runs.get()).isEqualTo(1);
        ClientRetirement.retire(client, shutdown, () -> false, () -> false); //released: a later shutdown runs
        assertThat(runs.get()).isEqualTo(2);
    }
    @Test
    void aDisplacedClientThatFailsToShutDownDoesNotKeepTheNewStartFromStarting() {
        SyncControl<FakeFetcher> control = control(false, 1_000, 100);
        FakeFetcher old = start(control, new Recorder());
        old.failShutdown = true;

        FakeFetcher newer = start(control, new Recorder());

        assertThat(newer.isRunning()).isTrue();
    }

    @Test
    void aSupersededStartThatFailsWhileStartingDoesNotThrowToItsCaller() {
        SyncControl<FakeFetcher> control = control(false, 1_000, 100);
        SyncControl.Swap<FakeFetcher> swap = control.replace(new Recorder(), FakeFetcher::new);
        swap.created().client.duringStart = () -> {
            control.replace(new Recorder(), FakeFetcher::new); //a newer startSync
            throw new IllegalStateException("Session disposed before the handshake completed");
        };

        control.start(swap.created()); //no exception

        assertThat(swap.created().client.isRunning()).isFalse();
    }

    @Test
    void theCurrentStartsStartupFailureStillReachesItsCaller() {
        SyncControl<FakeFetcher> control = control(false, 1_000, 100);
        SyncControl.Swap<FakeFetcher> swap = control.replace(new Recorder(), FakeFetcher::new);
        swap.created().client.duringStart = () -> {
            throw new IllegalStateException("connection refused");
        };

        assertThatThrownBy(() -> control.start(swap.created())).hasMessage("connection refused");
    }

    // ---- round 3

    @Test
    void aFetchIsRefusedWhileTheSyncHaltsAndAfter() {
        source.fallback = "fail";
        Recorder recorder = new Recorder();
        SyncControl<FakeFetcher> control = control(false, 200, 50);
        FakeFetcher client = start(control, recorder);
        List<String> refusals = new CopyOnWriteArrayList<>();
        recorder.onUnavailable = event -> {
            if (event.getOutcome() == Outcome.SYNC_HALTED)
                refusals.add(refusal(control));
        };

        client.blockFetch.blockFound(b10027);

        assertThat(refusals).hasSize(1).allMatch(r -> r != null && r.contains("halted"));
        assertThat(refusal(control)).contains("halted");
    }

    @Test
    void aFetchFromInsideTheOnBlockOfACompletedCertifyingBlockIsAllowed() {
        warm();
        Recorder recorder = new Recorder();
        SyncControl<FakeFetcher> control = control(false, 10_000, 50);
        FakeFetcher client = start(control, recorder);
        List<String> refusals = new CopyOnWriteArrayList<>();
        recorder.onBlockHook = () -> refusals.add(String.valueOf(refusal(control)));

        client.blockFetch.blockFound(b10027);

        assertThat(refusals).containsExactly("null");
    }

    @Test
    void aFetchIsRefusedWhileACertifyingBlockWaits() {
        warm();
        Recorder recorder = new Recorder();
        SyncControl<FakeFetcher> control = control(false, 10_000, 50);
        FakeFetcher client = start(control, recorder);
        List<String> refusals = new CopyOnWriteArrayList<>();
        source.afterSuccess = () -> refusals.add(refusal(control)); //inside the attempt, before the commit

        client.blockFetch.blockFound(b10027);

        assertThat(refusals).hasSize(1).allMatch(r -> r != null && r.contains("waits"));
    }

    @Test
    void theBudgetRunningOutInSkipModeClosesTheSideConnection() {
        source.fallback = "fail";
        Recorder recorder = new Recorder();
        FakeFetcher client = start(control(true, 300, 50), recorder);

        client.blockFetch.blockFound(b10027);

        assertThat(recorder.outcomes()).contains(Outcome.DELIVERED_WITHOUT_EB_TXS);
        assertThat(source.closes.get()).isEqualTo(1);
    }

    @Test
    void aReplyCompletedAfterTheDeadlineIsRefused() {
        AttemptDeadline expired = new AttemptDeadline(System.nanoTime() - 1, new java.util.concurrent.CompletableFuture<>());

        assertThatThrownBy(() -> expired.await(java.util.concurrent.CompletableFuture.completedFuture("late"), null,
                "reply")).isInstanceOf(java.util.concurrent.TimeoutException.class);
    }

    @Test
    void theDeadlineIsCheckedAfterTheEndorserBlockIsValidated() throws Exception {
        HeaderBody parent = b10026.getHeader().getHeaderBody();
        byte[] references = ((com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosBlock)
                LeiosFetchStateBase.deserialize(FakeN2NPeer.fixture("msg-leios-block-f951f865.hex")))
                .getEndorserBlock().getCbor();
        AttemptDeadline expired = new AttemptDeadline(System.nanoTime() - 1, new java.util.concurrent.CompletableFuture<>());

        assertThat(EndorserBlockResolver.checkEndorserBlock(references, parent.getEbAnnouncement(),
                EndorserBlockResolverTest.deadline(5_000))).hasSize(237);
        assertThatThrownBy(() -> EndorserBlockResolver.checkEndorserBlock(references, parent.getEbAnnouncement(), expired))
                .isInstanceOf(java.util.concurrent.TimeoutException.class).hasMessageContaining("checking");
    }

    /** The message of the exception a fetch would throw now, or null if it would be accepted. */
    private static String refusal(SyncControl<FakeFetcher> control) {
        try {
            control.current().delegate.checkFetch();
            return null;
        } catch (IllegalStateException e) {
            return e.getMessage();
        }
    }

    @Test
    void theAssemblerForwardsEveryListenerMethod() throws Exception {
        List<String> forwarded = new CopyOnWriteArrayList<>();
        BlockChainDataListener recording = (BlockChainDataListener) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{BlockChainDataListener.class}, (proxy, method, args) -> {
                    forwarded.add(method.getName() + method.getParameterCount());
                    return null;
                });
        CompleteBlockAssembler assembler = new CompleteBlockAssembler(recording, cache, source, false,
                new AtomicBoolean(), d -> {
        }, 1_000, 100, 0);

        List<String> expected = new ArrayList<>();
        for (Method method : BlockChainDataListener.class.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()))
                continue;
            Method own = CompleteBlockAssembler.class.getDeclaredMethod(method.getName(), method.getParameterTypes());
            assertThat(own.getDeclaringClass()).isEqualTo(CompleteBlockAssembler.class);
            Object[] args = new Object[method.getParameterCount()];
            if (method.getName().equals("onBlock"))
                args = new Object[]{Era.Dijkstra, b9091, List.of()};
            own.invoke(assembler, args);
            expected.add(method.getName() + method.getParameterCount());
        }
        assertThat(forwarded).containsExactlyInAnyOrderElementsOf(expected);
    }

    // ---- helpers

    // ---- ADR 0017 D4: read-ahead (BlockRangeSync, being evaluated)

    @Test
    void readAheadFetchesLaterEndorserBlocksWhileTheFrontWaitsAndDeliversInOrderOnTheMainThread() throws Exception {
        Recorder recorder = tracing(new Recorder());
        FakeFetcher client = start(readAheadControl(false, 5_000, 2), recorder);
        CompletableFuture<Void> gate = gate(1);

        Thread loop = range(client, block(1, true), block(2, false), block(3, true), block(4, false));
        waitUntil(() -> source.calls.get() == 2, 5_000); //block 3's fetch runs while block 1's waits
        assertThat(recorder.calls).isEmpty();
        gate.complete(null);
        loop.join(10_000);

        assertThat(recorder.calls).containsExactly("b1", "b2", "b3", "b4", "batchDone");
        assertThat(recorder.threads).containsOnly(loop);
        assertThat(recorder.events).isEmpty();
    }

    @Test
    void withReadAheadABlockWithoutACertificateAndNothingWaitingIsDeliveredAtOnce() {
        Recorder recorder = tracing(new Recorder());
        FakeFetcher client = start(readAheadControl(false, 5_000, 2), recorder);

        client.wired.onBlock(Era.Dijkstra, block(1, false), List.of());

        assertThat(recorder.calls).containsExactly("b1");
        assertThat(recorder.threads).containsOnly(Thread.currentThread());
        assertThat(source.calls.get()).isZero();
    }

    @Test
    void aLaterBlockFailingFirstIsReportedOnlyAfterTheFrontIsDeliveredStrict() throws Exception {
        Recorder recorder = tracing(new Recorder());
        FakeFetcher client = start(readAheadControl(false, 1_500, 2), recorder);
        CompletableFuture<Void> gate = gate(1);
        source.fallback = "fail";

        Thread loop = range(client, block(1, true), block(2, false), block(3, true), block(4, false));
        waitUntil(() -> source.calls.get() >= 2, 5_000); //block 3 failed behind the front: nothing reported
        Thread.sleep(100);
        assertThat(recorder.calls).isEmpty();
        gate.complete(null);
        loop.join(10_000);

        assertThat(recorder.calls.subList(0, 2)).containsExactly("b1", "b2");
        assertThat(recorder.calls.get(recorder.calls.size() - 1)).isEqualTo("SYNC_HALTED");
        assertThat(recorder.calls.subList(2, recorder.calls.size() - 1)).containsOnly("RETRYING");
        assertThat(recorder.events).allMatch(e -> e.getCertifyingBlockNumber() == 3);
        assertThat(recorder.calls).doesNotContain("b4", "batchDone");
        assertThat(source.closes.get()).isPositive(); //the side connections are closed at the halt
    }

    @Test
    void aLaterBlockFailingFirstIsDeliveredWithoutItsTransactionsInItsPlaceWhenSkipping() throws Exception {
        Recorder recorder = tracing(new Recorder());
        FakeFetcher client = start(readAheadControl(true, 1_500, 2), recorder);
        CompletableFuture<Void> gate = gate(1);
        source.fallback = "fail";

        Thread loop = range(client, block(1, true), block(2, false), block(3, true), block(4, false));
        waitUntil(() -> source.calls.get() >= 2, 5_000);
        gate.complete(null);
        loop.join(10_000);

        List<String> calls = recorder.calls;
        assertThat(calls.subList(0, 2)).containsExactly("b1", "b2");
        assertThat(calls.subList(calls.size() - 4, calls.size()))
                .containsExactly("DELIVERED_WITHOUT_EB_TXS", "b3", "b4", "batchDone");
        assertThat(calls.subList(2, calls.size() - 4)).containsOnly("RETRYING");
    }

    @Test
    void aFetchAheadThatFailedIsRetriedAtTheFrontWithoutACallback() throws Exception {
        Recorder recorder = tracing(new Recorder());
        FakeFetcher client = start(readAheadControl(false, 5_000, 2), recorder);
        CompletableFuture<Void> gate = gate(1);
        source.script(3, "fail", "ok");

        Thread loop = range(client, block(1, true), block(3, true));
        waitUntil(() -> source.calls.get() == 2, 5_000);
        Thread.sleep(50); //block 3's fetch ahead has failed
        gate.complete(null);
        loop.join(10_000);

        assertThat(recorder.calls).containsExactly("b1", "b3", "batchDone");
        assertThat(recorder.events).isEmpty();
        assertThat(source.calls.get()).isEqualTo(3);
    }

    @Test
    void aListenerExceptionOnAQueuedBlockIsThatBlocksParsingErrorInItsPlace() throws Exception {
        Recorder recorder = tracing(new Recorder());
        Runnable trace = recorder.onBlockHook;
        recorder.onBlockHook = () -> {
            trace.run();
            if (recorder.blocks.get(recorder.blocks.size() - 1).getHeader().getHeaderBody().getBlockNumber() == 2)
                throw new IllegalStateException("listener");
        };
        FakeFetcher client = start(readAheadControl(false, 5_000, 2), recorder);
        CompletableFuture<Void> gate = gate(1);

        Thread loop = range(client, block(1, true), block(2, false), block(3, false));
        waitUntil(() -> source.calls.get() == 1, 5_000);
        gate.complete(null);
        loop.join(10_000);

        assertThat(recorder.calls).containsExactly("b1", "b2", "onParsingError", "b3", "batchDone");
        assertThat(recorder.parsingErrors.get(0).getBlockNumber()).isEqualTo(2);
    }

    @Test
    void withReadAheadAFetchFromInsideBatchDoneIsAccepted() throws Exception {
        Recorder recorder = tracing(new Recorder());
        SyncControl<FakeFetcher> control = readAheadControl(false, 5_000, 2);
        AtomicReference<String> refusal = new AtomicReference<>("not called");
        recorder.onBatchDone = () -> refusal.set(refusal(control));
        FakeFetcher client = start(control, recorder);
        CompletableFuture<Void> gate = gate(1);

        Thread loop = range(client, block(1, true), block(2, false));
        waitUntil(() -> source.calls.get() == 1, 5_000);
        assertThat(refusal(control)).isNotNull(); //a range is still being delivered
        gate.complete(null);
        loop.join(10_000);

        assertThat(recorder.calls).containsExactly("b1", "b2", "batchDone");
        assertThat(refusal.get()).isNull();
    }

    @Test
    void aFullQueueHoldsTheMainThreadUntilTheFrontIsDelivered() throws Exception {
        Recorder recorder = tracing(new Recorder());
        FakeFetcher client = start(readAheadControl(false, 5_000, 1), recorder); //at most 2 certifying blocks wait
        CompletableFuture<Void> gate = gate(1);
        AtomicInteger read = new AtomicInteger();

        Thread loop = new Thread(() -> {
            for (long n = 1; n <= 4; n++) {
                client.wired.onBlock(Era.Dijkstra, block(n, true), List.of());
                read.incrementAndGet();
            }
            client.blockFetch.batchDone();
        });
        loop.start();
        waitUntil(() -> read.get() == 1, 5_000);
        Thread.sleep(100);
        assertThat(read.get()).isEqualTo(1); //block 2 filled the queue: its call waits for block 1
        assertThat(source.calls.get()).isEqualTo(1); //one fetch ahead at a time
        gate.complete(null);
        loop.join(10_000);

        assertThat(recorder.calls).containsExactly("b1", "b2", "b3", "b4", "batchDone");
    }

    @Test
    void stopCancelsTheFetchesAheadAndDeliversNothingMore() throws Exception {
        Recorder recorder = tracing(new Recorder());
        SyncControl<FakeFetcher> control = readAheadControl(false, 5_000, 2);
        FakeFetcher client = start(control, recorder);
        source.fallback = "hang";

        Thread loop = range(client, block(1, true), block(2, false), block(3, true));
        waitUntil(() -> source.calls.get() == 2, 5_000);
        stop(control);
        loop.join(10_000);

        assertThat(loop.isAlive()).isFalse();
        assertThat(recorder.calls).isEmpty();
        assertThat(recorder.events).isEmpty();
        assertThat(source.closes.get()).isPositive();
    }

    @Test
    void theCallbackSequenceIsTheSameWithAndWithoutReadAhead() throws Exception {
        List<String> expected = null;
        for (int readAhead : new int[]{0, 1, 2, 8}) {
            Recorder recorder = tracing(new Recorder());
            FakeFetcher client = start(readAheadControl(false, 5_000, readAhead), recorder);
            Thread loop = new Thread(() -> {
                client.blockFetch.batchStarted();
                for (long n = 1; n <= 12; n++) {
                    client.wired.onBlock(Era.Dijkstra, block(n, n % 3 != 2), List.of());
                    if (n == 6)
                        client.blockFetch.onParsingError(new BlockParseRuntimeException(7L, new byte[0],
                                new RuntimeException("block 7")));
                }
                client.blockFetch.batchDone();
            });
            loop.start();
            loop.join(10_000);
            if (expected == null)
                expected = List.copyOf(recorder.calls);
            assertThat(recorder.calls).as("read-ahead %d", readAhead).isEqualTo(expected);
        }
        assertThat(expected).startsWith("batchStarted", "b1", "b2").endsWith("b12", "batchDone")
                .contains("onParsingError");
    }

    @Test
    void stopWithBlocksQueuedDeliversNothingMoreFromThatStart() throws Exception {
        Recorder recorder = tracing(new Recorder());
        SyncControl<FakeFetcher> control = readAheadControl(false, 5_000, 2);
        FakeFetcher client = start(control, recorder);
        gate(1);

        client.wired.onBlock(Era.Dijkstra, block(1, true), List.of());
        client.wired.onBlock(Era.Dijkstra, block(2, false), List.of()); //both queued; the main thread is idle
        stop(control);
        client.wired.onBlock(Era.Dijkstra, block(3, false), List.of()); //already decoded
        client.blockFetch.batchDone();

        assertThat(recorder.calls).isEmpty();
    }

    @Test
    void aDisconnectDeliversTheBlocksReceivedBeforeItFirst() throws Exception {
        Recorder recorder = tracing(new Recorder());
        FakeFetcher client = start(readAheadControl(false, 5_000, 2), recorder);
        CompletableFuture<Void> gate = gate(1);

        client.wired.onBlock(Era.Dijkstra, block(1, true), List.of());
        client.wired.onBlock(Era.Dijkstra, block(2, false), List.of());
        Thread loop = new Thread(client.blockFetch::onDisconnect);
        loop.start();
        Thread.sleep(100);
        assertThat(recorder.calls).isEmpty(); //it waits for block 1, as the synchronous path would have
        gate.complete(null);
        loop.join(10_000);

        assertThat(recorder.calls).containsExactly("b1", "b2", "onDisconnect");
    }

    @Test
    void atMostMaxPendingCallbacksWaitBehindTheFront() throws Exception {
        Recorder recorder = tracing(new Recorder());
        FakeFetcher client = start(readAheadControl(false, 5_000, 2), recorder);
        CompletableFuture<Void> gate = gate(1);
        AtomicInteger read = new AtomicInteger();
        int total = CompleteBlockAssembler.MAX_PENDING + 50;

        Thread loop = new Thread(() -> {
            client.wired.onBlock(Era.Dijkstra, block(1, true), List.of());
            for (long n = 2; n <= total; n++) {
                client.wired.onBlock(Era.Dijkstra, block(n, false), List.of());
                read.incrementAndGet();
            }
            client.blockFetch.batchDone();
        });
        loop.start();
        waitUntil(() -> read.get() == CompleteBlockAssembler.MAX_PENDING - 2, 5_000);
        Thread.sleep(100);
        assertThat(read.get()).isEqualTo(CompleteBlockAssembler.MAX_PENDING - 2); //the queue is full
        gate.complete(null);
        loop.join(10_000);

        assertThat(recorder.calls).hasSize(total + 1).endsWith("b" + total, "batchDone");
    }

    @Test
    void aPooledSourceGivesEachCallItsOwnSourceAndClosesThemAll() throws Exception {
        FakeSource second = new FakeSource();
        PooledEndorserBlockSource pool = new PooledEndorserBlockSource(List.of(source, second));
        CompletableFuture<Void> gate = gate(1);
        AttemptDeadline deadline = new AttemptDeadline(System.nanoTime() + TimeUnit.SECONDS.toNanos(5),
                new CompletableFuture<>());

        CompletableFuture<Block> first = CompletableFuture.supplyAsync(() -> call(pool, block(1, true), deadline));
        waitUntil(() -> source.calls.get() == 1, 5_000);
        assertThat(pool.complete(block(2, true), null, deadline).getHeader().getHeaderBody().getBlockNumber())
                .isEqualTo(2);
        assertThat(second.calls.get()).isEqualTo(1); //the first source is busy
        gate.complete(null);
        assertThat(first.get(5, TimeUnit.SECONDS).getHeader().getHeaderBody().getBlockNumber()).isEqualTo(1);

        pool.close();
        assertThat(source.closes.get()).isEqualTo(1);
        assertThat(second.closes.get()).isEqualTo(1);
    }

    private static Block call(PooledEndorserBlockSource pool, Block block, AttemptDeadline deadline) {
        try {
            return pool.complete(block, null, deadline);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private SyncControl<FakeFetcher> readAheadControl(boolean skip, long budgetMillis, int readAhead) {
        LeiosConfig config = LeiosConfig.builder().skipUnavailableEndorserBlocks(skip).build();
        return new SyncControl<>(config, cache, owner -> source, budgetMillis, 20, SyncControl::runOnNewThread,
                readAhead);
    }

    /** Block {@code number}'s first attempt waits until the returned future completes. */
    private CompletableFuture<Void> gate(long number) {
        CompletableFuture<Void> gate = new CompletableFuture<>();
        source.gates.put(number, gate);
        source.script(number, "gate");
        return gate;
    }

    /** Records "b&lt;number&gt;" for each block and each event's outcome, in {@code calls}. */
    private static Recorder tracing(Recorder recorder) {
        recorder.onBlockHook = () -> {
            recorder.calls.remove(recorder.calls.size() - 1);
            recorder.calls.add("b" + recorder.blocks.get(recorder.blocks.size() - 1).getHeader().getHeaderBody()
                    .getBlockNumber());
        };
        recorder.onUnavailable = event -> recorder.calls.add(event.getOutcome().name());
        return recorder;
    }

    /** A synthetic block: number, slot, hashes, and the certificate flag. */
    private static Block block(long number, boolean certifying) {
        HeaderBody body = HeaderBody.builder().blockNumber(number).slot(number * 10)
                .blockHash(String.format("%064x", number)).prevHash(String.format("%064x", number - 1))
                .leiosCertInBody(certifying ? Boolean.TRUE : null).build();
        return Block.builder().era(Era.Dijkstra).header(BlockHeader.builder().headerBody(body).build()).build();
    }

    /** The main connection's thread: the blocks of one range, then the wire BatchDone. */
    private static Thread range(FakeFetcher client, Block... blocks) {
        Thread thread = new Thread(() -> {
            for (Block block : blocks)
                client.wired.onBlock(Era.Dijkstra, block, List.of());
            client.blockFetch.batchDone();
        });
        thread.start();
        return thread;
    }

    private static final List<String> EVERY_CALLBACK = List.of("onDisconnect", "onDisconnect", "intersactFound",
            "intersactNotFound", "onRollback", "rollforward", "rollforward", "rollforwardByronEra",
            "rollforwardByronEra", "rollforwardByronEra", "rollforwardByronEra", "noBlockFound", "onParsingError",
            "batchStarted", "batchDone", "onByronBlock", "onByronEbBlock", "onBlock");

    private void warm() {
        HeaderBody parent = b10026.getHeader().getHeaderBody();
        cache.put(parent.getBlockHash(), parent.getSlot(), parent.getEbAnnouncement());
    }

    private SyncControl<FakeFetcher> control(boolean skip, long budgetMillis, long pauseMillis) {
        LeiosConfig config = LeiosConfig.builder().skipUnavailableEndorserBlocks(skip).build();
        return new SyncControl<>(config, cache, owner -> source, budgetMillis, pauseMillis,
                SyncControl::runOnNewThread, 0);
    }

    /** As {@code BlockSync.startSync}. */
    private static FakeFetcher start(SyncControl<FakeFetcher> control, BlockChainDataListener listener) {
        return control.startNew(listener, FakeFetcher::new);
    }

    /** As {@code BlockSync.stop}. */
    private static void stop(SyncControl<FakeFetcher> control) {
        control.stop();
    }

    /** The main connection's thread: delivers the blocks inside the agent's monitor. */
    private static Thread loop(FakeFetcher client, Block... blocks) {
        Thread thread = new Thread(() -> {
            synchronized (client.agentLock) {
                for (Block block : blocks)
                    client.blockFetch.blockFound(block);
            }
        });
        thread.start();
        return thread;
    }

    private static void everyCallback(FakeFetcher client) {
        Tip tip = new Tip(new Point(1, "aa"), 1);
        Point point = new Point(1, "aa");
        BlockHeader header = b9091.getHeader();
        client.blockFetch.onDisconnect();
        client.chainSync.onDisconnect();
        client.chainSync.intersactFound(tip, point);
        client.chainSync.intersactNotFound(tip);
        client.chainSync.rollbackward(tip, point);
        client.chainSync.rollforward(tip, header);
        client.chainSync.rollforward(tip, header, new byte[0]);
        client.chainSync.rollforwardByronEra(tip, (com.bloxbean.cardano.yaci.core.model.byron.ByronBlockHead) null);
        client.chainSync.rollforwardByronEra(tip, (com.bloxbean.cardano.yaci.core.model.byron.ByronEbHead) null);
        client.chainSync.rollforwardByronEra(tip, (com.bloxbean.cardano.yaci.core.model.byron.ByronBlockHead) null,
                new byte[0]);
        client.chainSync.rollforwardByronEra(tip, (com.bloxbean.cardano.yaci.core.model.byron.ByronEbHead) null,
                new byte[0]);
        client.blockFetch.noBlockFound(point, point);
        client.blockFetch.onParsingError(new BlockParseRuntimeException(1L, new byte[0], new RuntimeException()));
        client.blockFetch.batchStarted();
        client.blockFetch.batchDone();
        client.blockFetch.byronBlockFound(null);
        client.blockFetch.byronEbBlockFound(null);
        client.blockFetch.blockFound(b9091);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A main client: running after start, both adapters installed on the delegate it was given. */
    static final class FakeFetcher implements Fetcher<Block> {
        final BlockChainDataListener wired;
        final BlockFetchAgentListenerAdapter blockFetch;
        final ChainSyncListenerAdapter chainSync;
        final Object agentLock = new Object();
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger shutdowns = new AtomicInteger();
        volatile Runnable duringStart;
        volatile boolean failShutdown;
        volatile boolean running;

        FakeFetcher(BlockChainDataListener wired) {
            this.wired = wired;
            this.blockFetch = new BlockFetchAgentListenerAdapter(wired);
            this.chainSync = new ChainSyncListenerAdapter(wired);
        }

        @Override
        public void start(Consumer<Block> consumer) {
            starts.incrementAndGet();
            running = true;
            if (duringStart != null)
                duringStart.run();
        }

        @Override
        public void shutdown() {
            synchronized (agentLock) { //as Session.dispose takes the agent's monitor
                shutdowns.incrementAndGet();
                if (failShutdown)
                    throw new NullPointerException("session");
                running = false;
            }
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }

    /**
     * Scripted attempts per block number: "ok", "fail", "hang" (waits for the deadline or a cancellation), "late" (a
     * success after the deadline) or "interrupt".
     */
    final class FakeSource implements CompleteBlockAssembler.EndorserBlockSource {
        final Map<Long, Deque<String>> scripts = new ConcurrentHashMap<>();
        final Map<Long, CompletableFuture<Void>> gates = new ConcurrentHashMap<>();
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        final List<Thread> threads = new CopyOnWriteArrayList<>();
        final List<Point> knownParents = new CopyOnWriteArrayList<>();
        volatile String fallback = "ok";
        volatile boolean requireWarm;
        /** Runs in a failing attempt that leaves no budget, just before it fails. */
        volatile Consumer<AttemptDeadline> beforeLastFailure;
        /** Runs after a successful attempt, before it returns. */
        volatile Runnable afterSuccess;

        void script(long blockNumber, String... steps) {
            scripts.put(blockNumber, new ArrayDeque<>(List.of(steps)));
        }

        @Override
        public Block complete(Block certifying, Point knownParent, AttemptDeadline deadline) throws Exception {
            calls.incrementAndGet();
            threads.add(Thread.currentThread());
            if (knownParent != null)
                knownParents.add(knownParent);
            HeaderBody header = certifying.getHeader().getHeaderBody();
            Deque<String> script = scripts.get(header.getBlockNumber());
            String step = script != null && !script.isEmpty() ? script.poll() : fallback;
            switch (step) {
                case "fail" -> {
                    if (beforeLastFailure != null) {
                        while (deadline.remainingMillis() > 0)
                            Thread.sleep(5);
                        beforeLastFailure.accept(deadline);
                    }
                    throw new IllegalStateException("no endorser block");
                }
                case "hang" -> deadline.await(new java.util.concurrent.CompletableFuture<>(), null, "endorser block");
                case "gate" -> deadline.await(gates.get(header.getBlockNumber()), null, "the gate");
                case "late" -> {
                    while (deadline.remainingMillis() > 0)
                        Thread.sleep(5);
                    Thread.sleep(20);
                }
                case "interrupt" -> throw new InterruptedException();
                case "foreign-cancel" -> throw new java.util.concurrent.CancellationException("not ours");
                default -> {
                }
            }
            if (header.getBlockNumber() != 10027)
                return certifying;
            if (requireWarm && cache.get(header.getPrevHash()) == null)
                throw new IllegalStateException("cold cache");
            HeaderBody parent = b10026.getHeader().getHeaderBody();
            Block complete = BlockSerializer.withEndorserBlockTxs(certifying, LeiosEndorserBlockRef.builder()
                    .ebHash(parent.getEbAnnouncement().getHash()).ebSlot(parent.getSlot())
                    .ebSize(parent.getEbAnnouncement().getSize()).build(), ebTransactions);
            if (afterSuccess != null)
                afterSuccess.run();
            return complete;
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }

    /** Records what the application receives. */
    static final class Recorder implements BlockChainDataListener {
        final List<Block> blocks = new CopyOnWriteArrayList<>();
        final List<List<Transaction>> transactions = new CopyOnWriteArrayList<>();
        final List<Thread> threads = new CopyOnWriteArrayList<>();
        final List<EndorserBlockUnavailableEvent> events = new CopyOnWriteArrayList<>();
        final List<BlockParseRuntimeException> parsingErrors = new CopyOnWriteArrayList<>();
        final List<String> calls = new CopyOnWriteArrayList<>();
        volatile RuntimeException throwOnBlock;
        volatile boolean throwOnUnavailable;
        volatile Consumer<EndorserBlockUnavailableEvent> onUnavailable;
        volatile Runnable onBatchDone;
        volatile Runnable onBlockHook;

        List<Outcome> outcomes() {
            return events.stream().map(EndorserBlockUnavailableEvent::getOutcome).toList();
        }

        @Override
        public void onBlock(Era era, Block block, List<Transaction> txs) {
            calls.add("onBlock");
            blocks.add(block);
            transactions.add(txs);
            threads.add(Thread.currentThread());
            if (onBlockHook != null)
                onBlockHook.run();
            if (throwOnBlock != null)
                throw throwOnBlock;
        }

        @Override
        public void onEndorserBlockUnavailable(EndorserBlockUnavailableEvent event) {
            events.add(event);
            if (onUnavailable != null)
                onUnavailable.accept(event);
            if (throwOnUnavailable)
                throw new IllegalStateException("listener");
        }

        @Override
        public void onByronBlock(com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock byronBlock) {
            calls.add("onByronBlock");
        }

        @Override
        public void onByronEbBlock(com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock byronEbBlock) {
            calls.add("onByronEbBlock");
        }

        @Override
        public void onRollback(Point point) {
            calls.add("onRollback");
        }

        @Override
        public void batchStarted() {
            calls.add("batchStarted");
        }

        @Override
        public void batchDone() {
            calls.add("batchDone");
            if (onBatchDone != null)
                onBatchDone.run();
        }

        @Override
        public void noBlockFound(Point from, Point to) {
            calls.add("noBlockFound");
        }

        @Override
        public void intersactFound(Tip tip, Point point) {
            calls.add("intersactFound");
        }

        @Override
        public void intersactNotFound(Tip tip) {
            calls.add("intersactNotFound");
        }

        @Override
        public void onDisconnect() {
            calls.add("onDisconnect");
        }

        @Override
        public void onParsingError(BlockParseRuntimeException e) {
            calls.add("onParsingError");
            parsingErrors.add(e);
        }

        @Override
        public void rollforward(Tip tip, BlockHeader blockHeader, byte[] originalHeaderBytes) {
            calls.add("rollforward");
        }

        @Override
        public void rollforwardByronEra(Tip tip, com.bloxbean.cardano.yaci.core.model.byron.ByronBlockHead head,
                                        byte[] originalHeaderBytes) {
            calls.add("rollforwardByronEra");
        }

        @Override
        public void rollforwardByronEra(Tip tip, com.bloxbean.cardano.yaci.core.model.byron.ByronEbHead head,
                                        byte[] originalHeaderBytes) {
            calls.add("rollforwardByronEra");
        }
    }
}
