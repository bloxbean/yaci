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
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.helper.listener.TransactionListBuilder;
import com.bloxbean.cardano.yaci.helper.model.Transaction;
import com.bloxbean.cardano.yaci.helper.model.leios.EndorserBlockUnavailableEvent;
import com.bloxbean.cardano.yaci.helper.model.leios.EndorserBlockUnavailableEvent.Outcome;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
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
     * @param retired the start's retirement flag, owned by {@link SyncControl}: once set, a certifying block's call
     *                in progress, or any that comes later, is cancelled
     */
    CompleteBlockAssembler(BlockChainDataListener listener, EbAnnouncementCache cache, EndorserBlockSource source,
                           boolean skipUnavailable, AtomicBoolean retired, Consumer<CompleteBlockAssembler> onHalt,
                           long budgetMillis, long pauseMillis) {
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
    }

    /**
     * A newer start of the same helper replaced this one (its call was already cancelled when it was retired):
     * nothing more is forwarded but disconnects, as on next. A reconnect of the replaced client delivers no block.
     */
    void replaced() {
        replaced = true;
        forwarding = false;
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
    }

    @Override
    public void onBlock(Era era, Block block, List<Transaction> transactions) {
        if (!forwarding)
            return;
        if (!Boolean.TRUE.equals(block.getHeader().getHeaderBody().getLeiosCertInBody())) {
            deliver(era, block, transactions);
            return;
        }

        Call call = new Call();
        current = call;
        try {
            if (retired.get())
                call.cancel();
            completeAndDeliver(call, era, block, transactions);
        } finally {
            current = null;
        }
    }

    /**
     * Attempts while time is left; after a failure, RETRYING and a pause while time is left; otherwise exactly one
     * terminal outcome.
     */
    private void completeAndDeliver(Call call, Era era, Block block, List<Transaction> transactions) {
        HeaderBody header = block.getHeader().getHeaderBody();
        AttemptDeadline deadline = new AttemptDeadline(
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis), call.cancelled);
        String reason = "the budget ran out";
        String lastLogged = null;
        boolean interrupted = false;
        int attempt = 0;
        while (!interrupted && deadline.remainingMillis() > 0) {
            if (call.state.get() != State.ACTIVE) {
                cancelled();
                return;
            }
            attempt++;
            Block complete;
            List<Transaction> completeTransactions;
            try {
                complete = source.complete(block, knownParent, deadline);
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
            listener.onByronBlock(byronBlock);
    }

    @Override
    public void onByronEbBlock(ByronEbBlock byronEbBlock) {
        if (forwarding)
            listener.onByronEbBlock(byronEbBlock);
    }

    @Override
    public void onRollback(Point point) {
        if (forwarding)
            listener.onRollback(point);
    }

    @Override
    public void batchStarted() {
        if (forwarding)
            listener.batchStarted();
    }

    @Override
    public void batchDone() {
        if (forwarding)
            listener.batchDone();
    }

    @Override
    public void noBlockFound(Point from, Point to) {
        if (forwarding)
            listener.noBlockFound(from, to);
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

    @Override
    public void onDisconnect() {
        if (forwarding || (replaced && !halted))
            listener.onDisconnect();
    }

    @Override
    public void onParsingError(BlockParseRuntimeException e) {
        if (forwarding)
            listener.onParsingError(e);
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
