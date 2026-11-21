package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.exception.BlockParseRuntimeException;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.model.byron.ByronBlockHead;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbHead;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.helper.listener.TransactionListBuilder;
import com.bloxbean.cardano.yaci.helper.model.Transaction;
import com.bloxbean.cardano.yaci.helper.model.leios.EndorserBlockUnavailableEvent;
import com.bloxbean.cardano.yaci.helper.model.leios.EndorserBlockUnavailableEvent.Outcome;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The listener delegate of one sync start (ADR 0016 D3b, D3c). It forwards every callback at once, on the calling
 * thread, with nothing queued, so a block without {@code leiosCertInBody == true} reaches the application exactly as
 * it does without it.
 * <p>
 * A block that certifies an endorser block (EB) is completed inside its {@code onBlock} call, within a budget of
 * 45 s: attempts follow one another after a 1 s pause, each with the time left as its deadline. Each failed attempt
 * is reported through {@code onEndorserBlockUnavailable}. When the budget runs out the block is delivered as received
 * ({@code skipUnavailableEndorserBlocks}), or the sync halts: the block is not delivered, this delegate drops every
 * later callback, and the owner stops the main client on another thread.
 * <p>
 * Retiring the start (stop, restart, startSync) cancels a certifying block's call in progress: it returns without
 * delivering or reporting anything, unless it had already committed to an outcome, and from then on this delegate
 * drops every callback, so the blocks behind it are not delivered either. A start replaced by a newer one forwards
 * nothing more but disconnects. A stopped start without such a call forwards everything as before.
 * <p>
 * Nothing here takes the owning helper's lock: this runs on the main connection's event loop.
 */
@Slf4j
final class CompleteBlockAssembler implements BlockChainDataListener {
    static final long BUDGET_MILLIS = 45_000;
    static final long PAUSE_MILLIS = 1_000;
    /** D4: at most this many callbacks wait in order behind a certifying block (with read-ahead). */
    static final int MAX_PENDING = 512;
    /** D4: at most this many certifying blocks are fetched ahead. */
    static final int MAX_READ_AHEAD = 8;

    /** One attempt to complete a certifying block with its EB's transactions, checked (D3a) and mapped. */
    interface EndorserBlockSource {
        Block complete(Block certifying, Point knownParent, AttemptDeadline deadline) throws Exception;

        /** Closes the side connections, without waiting. */
        default void close() {
        }
    }

    /** The state of one certifying block's call: it moves once, from ACTIVE. */
    private enum State {ACTIVE, COMMITTED, CANCELLED, HALTED}

    private static final class Call {
        final AtomicReference<State> state = new AtomicReference<>(State.ACTIVE);
        final CompletableFuture<Void> cancelled = new CompletableFuture<>();

        void cancel() {
            state.compareAndSet(State.ACTIVE, State.CANCELLED);
            cancelled.complete(null);
        }
    }

    private final BlockChainDataListener listener;
    private final EbAnnouncementCache cache;
    private final EndorserBlockSource source;
    private final boolean skipUnavailable;
    private final AtomicBoolean retired;
    private final Consumer<CompleteBlockAssembler> onHalt;
    private final long budgetMillis;
    private final long pauseMillis;
    private volatile boolean forwarding = true;
    private volatile boolean replaced;
    private volatile boolean halted;
    private volatile Call current;
    private volatile Point knownParent;
    /**
     * D4 read-ahead (ADR 0017, being evaluated): the number of certifying blocks whose EB fetches run ahead of
     * delivery; 0 is the synchronous path of ADR 0016 D3b (BlockSync, and BlockRangeSync unless enabled).
     */
    private final int readAhead;
    /** Runs the fetches ahead of delivery (read-ahead only). */
    private final ExecutorService prefetcher;
    /** Completed when the start is retired or halts: fetches ahead stop. */
    private final CompletableFuture<Void> prefetchCancelled = new CompletableFuture<>();
    /** Callbacks waiting to be delivered in order (read-ahead only), guarded by itself. */
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private int pendingCertifying;
    /** The block of the onBlock call in progress (read-ahead, main thread only): its listener exception propagates. */
    private Block reading;

    /** One callback in order: a block (a certifying one with its fetch ahead) or another callback (a marker). */
    private record Pending(Era era, Block block, List<Transaction> transactions, CompletableFuture<Block> prefetch,
                           Runnable marker) {
    }

    /**
     * @param retired   the start's retirement flag, owned by {@link SyncControl}: once set, a certifying block's call
     *                  in progress, or any that comes later, is cancelled
     * @param readAhead certifying blocks fetched ahead of delivery (D4, at most {@link #MAX_READ_AHEAD}); 0 for the
     *                  synchronous path
     */
    CompleteBlockAssembler(BlockChainDataListener listener, EbAnnouncementCache cache, EndorserBlockSource source,
                           boolean skipUnavailable, AtomicBoolean retired, Consumer<CompleteBlockAssembler> onHalt,
                           long budgetMillis, long pauseMillis, int readAhead) {
        this.readAhead = Math.clamp(readAhead, 0, MAX_READ_AHEAD);
        this.prefetcher = this.readAhead > 0 ? Executors.newFixedThreadPool(this.readAhead, task -> {
            Thread thread = new Thread(task, "yaci-leios-prefetch");
            thread.setDaemon(true);
            return thread;
        }) : null;
        this.listener = listener;
        this.cache = cache;
        this.source = source;
        this.skipUnavailable = skipUnavailable;
        this.retired = retired;
        this.onHalt = onHalt;
        this.budgetMillis = budgetMillis;
        this.pauseMillis = pauseMillis;
    }

    /** The start was retired (its flag is set): cancels a certifying block's call in progress. */
    void cancelCall() {
        Call call = current;
        if (call != null)
            call.cancel();
        if (stopReadAhead())
            forwarding = false; //blocks were dropped: delivering later ones would leave a gap
    }

    /**
     * A newer start of the same helper replaced this one (its call was already cancelled when it was retired):
     * nothing more is forwarded but disconnects, as on next. A reconnect of the replaced client delivers no block.
     */
    void replaced() {
        replaced = true;
        forwarding = false;
        stopReadAhead();
    }

    /** True once this start halted (strict mode, budget exhausted). */
    boolean isHalted() {
        return halted;
    }

    /**
     * {@link BlockRangeSync#fetch}: refuses after a halt, and while a certifying block's call is in progress (until it
     * commits). The call is read before the halt flag: a halt is published before its call ends, so a fetch cannot
     * slip between them.
     */
    void checkFetch() {
        boolean inCall = current != null;
        if (halted)
            throw new IllegalStateException("The sync halted (Leios endorser block unavailable). " +
                    "Call restart(listener) then fetch(from, to)");
        if (inCall)
            throw new IllegalStateException("A certifying block of the current range waits for its endorser block");
        if (pendingCount() > 0)
            throw new IllegalStateException("Blocks of the current range are still being delivered");
    }

    @Override
    public void onBlock(Era era, Block block, List<Transaction> transactions) {
        if (!forwarding)
            return;
        if (readAhead > 0) {
            readAhead(era, block, transactions);
            return;
        }
        if (!Boolean.TRUE.equals(block.getHeader().getHeaderBody().getLeiosCertInBody())) {
            deliver(era, block, transactions);
            return;
        }

        complete(era, block, transactions, null);
    }

    /** One certifying block's call: cancelled at once if the start is already retired. */
    private void complete(Era era, Block block, List<Transaction> transactions, CompletableFuture<Block> fetchedAhead) {
        Call call = new Call();
        current = call;
        try {
            if (retired.get())
                call.cancel();
            completeAndDeliver(call, era, block, transactions, fetchedAhead);
        } finally {
            current = null;
        }
    }

    // ---- D4 read-ahead (ADR 0017, being evaluated) ----

    /**
     * A block with read-ahead: an ordinary one with nothing waiting is delivered at once, as without read-ahead;
     * otherwise it waits in order, a certifying one with its EB fetch started. The calling thread then delivers what
     * is ready, and waits for the front while the queue is full.
     */
    private void readAhead(Era era, Block block, List<Transaction> transactions) {
        HeaderBody header = block.getHeader().getHeaderBody();
        //on read, not on delivery: a certifying child waiting behind its parent finds the parent's announcement
        cache.put(header.getBlockHash(), header.getSlot(), header.getEbAnnouncement());
        boolean certifying = Boolean.TRUE.equals(header.getLeiosCertInBody());
        if (!certifying && pendingCount() == 0) {
            deliver(era, block, transactions);
            return;
        }
        reading = block;
        try {
            enqueue(new Pending(era, block, transactions, certifying ? prefetch(block) : null, null));
            drain(false);
            while (forwarding && isFull())
                drain(true);
        } finally {
            reading = null;
        }
    }

    /** Another callback with read-ahead: in its place behind the blocks waiting, or at once when none waits. */
    private void inOrder(Runnable call) {
        if (readAhead > 0 && pendingCount() > 0) {
            enqueue(new Pending(null, null, null, null, call));
            drain(false);
        } else {
            call.run();
        }
    }

    private CompletableFuture<Block> prefetch(Block block) {
        Point parent = knownParent;
        try {
            return CompletableFuture.supplyAsync(() -> {
                //no budget of its own: the block's budget starts at the front; the resolver's stall caps bound it
                AttemptDeadline deadline = new AttemptDeadline(System.nanoTime() + TimeUnit.DAYS.toNanos(1),
                        prefetchCancelled);
                try {
                    return source.complete(block, parent, deadline);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, prefetcher);
        } catch (RejectedExecutionException e) { //stopped: the front attempt reports it
            return CompletableFuture.failedFuture(e);
        }
    }

    private void enqueue(Pending entry) {
        synchronized (pending) {
            pending.add(entry);
            if (entry.prefetch() != null)
                pendingCertifying++;
        }
    }

    private int pendingCount() {
        synchronized (pending) {
            return pending.size();
        }
    }

    /** Full: as many certifying blocks waiting as are fetched ahead, twice over, or {@link #MAX_PENDING} callbacks. */
    private boolean isFull() {
        synchronized (pending) {
            return pendingCertifying >= 2 * readAhead || pending.size() >= MAX_PENDING;
        }
    }

    /**
     * Delivers from the front, in order, while the front is ready. With {@code waitForFront} it delivers the front
     * even if its fetch is still running: that block's call (budget, RETRYING, skip, halt) starts here, at the front,
     * exactly as without read-ahead; its fetch ahead is its first attempt.
     */
    private void drain(boolean waitForFront) {
        while (forwarding) {
            Pending front;
            synchronized (pending) {
                front = pending.peek();
                if (front == null)
                    return;
                if (front.prefetch() != null && !front.prefetch().isDone() && !waitForFront)
                    return;
                pending.poll();
                if (front.prefetch() != null)
                    pendingCertifying--;
            }
            waitForFront = false; //one front at a time: then only what is ready
            if (front.marker() != null)
                runMarker(front.marker());
            else if (front.prefetch() == null)
                deliverInOrder(front.era(), front.block(), front.transactions());
            else
                completeInOrder(front);
        }
    }

    /** batchDone and the like: every block before it is delivered first. */
    private void drainAll() {
        while (forwarding && pendingCount() > 0)
            drain(true);
    }

    private void completeInOrder(Pending front) {
        try {
            complete(front.era(), front.block(), front.transactions(), front.prefetch());
        } catch (RuntimeException e) {
            listenerFailed(front.block(), e);
        }
        if (!forwarding)
            stopReadAhead(); //halted or cancelled: nothing behind it is delivered
    }

    private void deliverInOrder(Era era, Block block, List<Transaction> transactions) {
        try {
            deliver(era, block, transactions);
        } catch (RuntimeException e) {
            listenerFailed(block, e);
        }
    }

    /**
     * A listener exception on the block being read propagates to BlockfetchAgent, as without read-ahead. On a block
     * delivered later it becomes that block's onParsingError in its place, with the block's bytes only when
     * YaciConfig.returnBlockCbor is on.
     */
    private void listenerFailed(Block block, RuntimeException e) {
        if (block == reading)
            throw e;

        log.error("Error in delivering block {}", block.getHeader().getHeaderBody().getBlockNumber(), e);
        if (!forwarding)
            return;
        String cbor = block.getCbor(); //the block's bytes when YaciConfig.returnBlockCbor is on
        listener.onParsingError(new BlockParseRuntimeException(block.getHeader().getHeaderBody().getBlockNumber(),
                cbor != null ? HexUtil.decodeHexString(cbor) : null, e));
    }

    private void runMarker(Runnable marker) {
        try {
            marker.run();
        } catch (RuntimeException e) {
            log.error("A listener callback delivered in order failed", e);
        }
    }

    /** @return true if callbacks were waiting, and are now dropped */
    private boolean stopReadAhead() {
        if (readAhead == 0)
            return false;
        prefetchCancelled.complete(null);
        prefetcher.shutdownNow();
        synchronized (pending) {
            boolean dropped = !pending.isEmpty();
            pending.clear();
            pendingCertifying = 0;
            return dropped;
        }
    }

    /**
     * Attempts while time is left; after a failure, RETRYING and a pause while time is left; otherwise exactly one
     * terminal outcome.
     */
    private void completeAndDeliver(Call call, Era era, Block block, List<Transaction> transactions,
                                    CompletableFuture<Block> fetchedAhead) {
        HeaderBody header = block.getHeader().getHeaderBody();
        AttemptDeadline deadline = new AttemptDeadline(
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis), call.cancelled);
        String reason = "the budget ran out";
        String lastLogged = null;
        boolean interrupted = false;
        int attempt = 0;
        CompletableFuture<Block> ahead = fetchedAhead;
        while (!interrupted && deadline.remainingMillis() > 0) {
            if (call.state.get() != State.ACTIVE) {
                cancelled();
                return;
            }
            attempt++;
            Block complete;
            List<Transaction> completeTransactions;
            try {
                complete = ahead != null ? deadline.await(ahead, null, "the endorser block fetched ahead")
                        : source.complete(block, knownParent, deadline);
                completeTransactions = TransactionListBuilder.build(complete);
                deadline.check("before the block was complete");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                reason = "interrupted";
                interrupted = true;
                break;
            } catch (Exception e) {
                if (e instanceof CancellationException && call.state.get() != State.ACTIVE) {
                    cancelled();
                    return;
                }
                if (ahead != null) { //the fetch ahead failed: internal, no callback; the front starts afresh
                    ahead = null;
                    attempt--;
                    continue;
                }
                reason = e.getMessage() != null ? e.getMessage() : e.toString();
                if (!reason.equals(lastLogged))
                    log.warn("Block {} (slot {}): attempt {} to get the endorser block failed: {}",
                            header.getBlockNumber(), header.getSlot(), attempt, reason, e);
                lastLogged = reason;
                if (deadline.remainingMillis() <= 0)
                    break;
                if (call.state.get() != State.ACTIVE) {
                    cancelled();
                    return;
                }
                report(event(header, reason, attempt, Outcome.RETRYING));
                long leftMillis = deadline.remainingMillis(); //after the report
                if (leftMillis > 0 && !pause(call, Math.min(pauseMillis, leftMillis))) {
                    reason = "interrupted";
                    interrupted = true;
                }
                continue;
            }
            if (!call.state.compareAndSet(State.ACTIVE, State.COMMITTED)) {
                cancelled();
                return;
            }
            current = null; //committed: from here on this is an ordinary delivery
            deliver(era, complete, completeTransactions); //a listener exception propagates, as on next
            return;
        }

        //the budget ran out (or the thread was interrupted): exactly one terminal outcome
        source.close(); //a side connection left open by the last attempt is not reused
        Outcome outcome = skipUnavailable ? Outcome.DELIVERED_WITHOUT_EB_TXS : Outcome.SYNC_HALTED;
        if (!call.state.compareAndSet(State.ACTIVE, skipUnavailable ? State.COMMITTED : State.HALTED)) {
            cancelled();
            return;
        }
        EndorserBlockUnavailableEvent event = event(header, reason, attempt, outcome);
        if (outcome == Outcome.DELIVERED_WITHOUT_EB_TXS) {
            current = null; //committed: from here on this is an ordinary delivery
            report(event);
            //the listener does not run with the interrupt flag set; it is restored afterwards
            boolean wasInterrupted = Thread.interrupted();
            try {
                deliver(era, block, transactions);
            } finally {
                if (wasInterrupted)
                    Thread.currentThread().interrupt();
            }
            return;
        }
        log.error("Sync halted: no endorser block {} for block {} (slot {}, hash {}) within {} ms: {} ({})",
                event.getEbHash(), header.getBlockNumber(), header.getSlot(), header.getBlockHash(),
                budgetMillis, reason, outcome);
        halted = true; //before the report: isRunning() is false when the application hears of it
        report(event);
        forwarding = false; //after the report; then the owner stops the client on another thread
        onHalt.accept(this);
    }

    /** A cancelled call returns silently, and the start delivers nothing more: the blocks behind it are dropped. */
    private void cancelled() {
        forwarding = false;
    }

    /** @return false if interrupted (the budget counts as exhausted) */
    private boolean pause(Call call, long millis) {
        try {
            call.cancelled.get(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            //the pause ended
        }
        return true;
    }

    private void deliver(Era era, Block block, List<Transaction> transactions) {
        HeaderBody header = block.getHeader().getHeaderBody();
        cache.put(header.getBlockHash(), header.getSlot(), header.getEbAnnouncement());
        listener.onBlock(era, block, transactions);
    }

    private EndorserBlockUnavailableEvent event(HeaderBody header, String reason, int attempt, Outcome outcome) {
        EbAnnouncementCache.Announced parent = cache.get(header.getPrevHash());
        return EndorserBlockUnavailableEvent.builder()
                .certifyingSlot(header.getSlot())
                .certifyingBlockHash(header.getBlockHash())
                .certifyingBlockNumber(header.getBlockNumber())
                .ebHash(parent != null && parent.announcement() != null ? parent.announcement().getHash() : null)
                .reason(reason)
                .attempt(attempt)
                .outcome(outcome)
                .build();
    }

    private void report(EndorserBlockUnavailableEvent event) {
        try {
            listener.onEndorserBlockUnavailable(event);
        } catch (Exception e) {
            log.error("onEndorserBlockUnavailable failed", e);
        }
    }

    @Override
    public void onEndorserBlockUnavailable(EndorserBlockUnavailableEvent event) {
        if (forwarding)
            listener.onEndorserBlockUnavailable(event);
    }

    @Override
    public void onByronBlock(ByronMainBlock byronBlock) {
        if (forwarding)
            inOrder(() -> listener.onByronBlock(byronBlock));
    }

    @Override
    public void onByronEbBlock(ByronEbBlock byronEbBlock) {
        if (forwarding)
            inOrder(() -> listener.onByronEbBlock(byronEbBlock));
    }

    @Override
    public void onRollback(Point point) {
        if (forwarding)
            listener.onRollback(point);
    }

    @Override
    public void batchStarted() {
        if (forwarding)
            inOrder(listener::batchStarted);
    }

    /** With read-ahead, after every block of the range: a listener may call fetch() from inside it, as today. */
    @Override
    public void batchDone() {
        drainAll();
        if (forwarding)
            listener.batchDone();
    }

    @Override
    public void noBlockFound(Point from, Point to) {
        if (forwarding)
            inOrder(() -> listener.noBlockFound(from, to));
    }

    @Override
    public void intersactFound(Tip tip, Point point) {
        if (!forwarding)
            return;
        knownParent = point; //the parent of the next block: a certifying first block needs no search
        listener.intersactFound(tip, point);
    }

    @Override
    public void intersactNotFound(Tip tip) {
        if (forwarding)
            listener.intersactNotFound(tip);
    }

    /** With read-ahead, after every block received before it, as batchDone. */
    @Override
    public void onDisconnect() {
        if (forwarding)
            drainAll();
        if (forwarding || (replaced && !halted))
            listener.onDisconnect();
    }

    @Override
    public void onParsingError(BlockParseRuntimeException e) {
        if (forwarding)
            inOrder(() -> listener.onParsingError(e));
    }

    @Override
    public void rollforward(Tip tip, BlockHeader blockHeader, byte[] originalHeaderBytes) {
        if (forwarding)
            listener.rollforward(tip, blockHeader, originalHeaderBytes);
    }

    @Override
    public void rollforwardByronEra(Tip tip, ByronBlockHead byronBlockHead, byte[] originalHeaderBytes) {
        if (forwarding)
            listener.rollforwardByronEra(tip, byronBlockHead, originalHeaderBytes);
    }

    @Override
    public void rollforwardByronEra(Tip tip, ByronEbHead byronEbHead, byte[] originalHeaderBytes) {
        if (forwarding)
            listener.rollforwardByronEra(tip, byronEbHead, originalHeaderBytes);
    }
}
