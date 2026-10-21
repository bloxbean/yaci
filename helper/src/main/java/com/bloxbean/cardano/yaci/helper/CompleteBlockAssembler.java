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
import com.bloxbean.cardano.yaci.core.model.leios.LeiosAnnouncement;
import com.bloxbean.cardano.yaci.core.model.leios.LeiosEndorserBlockRef;
import com.bloxbean.cardano.yaci.core.model.serializers.DijkstraBlockBodySerializer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosPoint;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.helper.listener.TransactionListBuilder;
import com.bloxbean.cardano.yaci.helper.model.Transaction;
import com.bloxbean.cardano.yaci.helper.model.leios.EndorserBlockEvent;
import com.bloxbean.cardano.yaci.helper.model.leios.EndorserBlockUnavailableEvent;
import com.bloxbean.cardano.yaci.helper.model.leios.LeiosVotesEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Leios (ADR 0013): delivers a ranking block that certifies an endorser block (EB) as one complete block, with the
 * EB's transactions first, so applications see every ledger transaction through the usual
 * {@link BlockChainDataListener#onBlock}. It wraps the application's listener; nothing changes for applications.
 * <ul>
 *   <li>Every callback is queued in arrival order and delivered on one worker thread, so order never changes.
 *       The queue is bounded: when it is full, the network thread waits, which slows reading from the node the
 *       same way a busy listener does today.</li>
 *   <li>A certifying block waits in the queue until its EB transactions are fetched (blocks behind it wait too).
 *       The fetch starts as soon as the block arrives.</li>
 *   <li>The certified EB is the one announced by the block's parent. Announcements are remembered by block hash;
 *       if the parent was never seen (first block after a start), it is fetched once.</li>
 * </ul>
 */
@Slf4j
class CompleteBlockAssembler implements BlockChainDataListener, AutoCloseable {
    static final int QUEUE_CAPACITY = 256;
    private static final int REMEMBERED_ANNOUNCEMENTS = 4_096;
    private static final long EB_TIMEOUT_SECONDS = 120;
    private static final int SKIP_AFTER_ATTEMPTS = 3;
    private static final long MAX_RETRY_DELAY_MILLIS = 30_000;

    private final BlockChainDataListener listener;
    private final EndorserBlockSource resolver;
    private final ParentBlockSource parentLookup;
    private final boolean skipUnavailable;
    private final long retryBaseMillis;
    private final BlockingQueue<Event> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final Map<String, Announced> announcements = new LinkedHashMap<>(256, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Announced> eldest) {
            return size() > REMEMBERED_ANNOUNCEMENTS;
        }
    };
    private final Thread worker;
    private volatile Point startPoint;
    private volatile Event current;
    private volatile boolean closed;
    /** Rollbacks seen so far as [sequence, slot], newest last; a certifying block compares them with its arrival. */
    private final List<long[]> rollbacks = new java.util.ArrayList<>();
    private long rollbackSequence;

    CompleteBlockAssembler(BlockChainDataListener listener, String host, int port, long protocolMagic,
                           VersionTable versionTable, boolean skipUnavailable) {
        this(listener, new EndorserBlockResolver(host, port, protocolMagic, versionTable),
                new ParentBlockLookup(host, port, versionTable)::fetch, skipUnavailable, 1_000);
    }

    CompleteBlockAssembler(BlockChainDataListener listener, EndorserBlockSource resolver,
                           ParentBlockSource parentLookup, boolean skipUnavailable, long retryBaseMillis) {
        this.listener = listener;
        this.resolver = resolver;
        this.parentLookup = parentLookup;
        this.skipUnavailable = skipUnavailable;
        this.retryBaseMillis = retryBaseMillis;
        this.worker = new Thread(this::deliverLoop, "yaci-leios-complete-blocks");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /**
     * The point a sync starts after (ChainSync), if known. The first block's parent is then this point.
     */
    void setStartPoint(Point startPoint) {
        this.startPoint = startPoint;
    }

    @Override
    public void onBlock(Era era, Block block, List<Transaction> transactions) {
        HeaderBody header = block.getHeader().getHeaderBody();
        if (header.getLeiosAnnouncement() != null) {
            remember(header.getBlockHash(), new Announced(header.getSlot(), header.getLeiosAnnouncement()));
        }
        if (!Boolean.TRUE.equals(header.getLeiosCertified())) {
            enqueue(() -> deliverBlock(era, block, transactions));
            return;
        }
        CertifyingBlock certifying = new CertifyingBlock(era, block, transactions, currentRollbackSequence());
        Announced parent = lookup(header.getPrevHash());
        if (parent != null) {
            certifying.use(parent);
            certifying.pending = resolver.resolve(certifying.ebPoint); //start fetching now
        }
        enqueue(certifying);
    }

    @Override
    public void onRollback(Point point) {
        synchronized (this) {
            rollbacks.add(new long[]{++rollbackSequence, point.getSlot()});
            if (rollbacks.size() > 256) rollbacks.remove(0);
        }
        abandonAfter(current, point);
        for (Event event : queue) {
            abandonAfter(event, point);
        }
        enqueue(() -> listener.onRollback(point));
    }

    @Override
    public void onByronBlock(ByronMainBlock byronBlock) {
        enqueue(() -> listener.onByronBlock(byronBlock));
    }

    @Override
    public void onByronEbBlock(ByronEbBlock byronEbBlock) {
        enqueue(() -> listener.onByronEbBlock(byronEbBlock));
    }

    @Override
    public void onEndorserBlock(EndorserBlockEvent event) {
        enqueue(() -> listener.onEndorserBlock(event));
    }

    @Override
    public void onLeiosVotes(LeiosVotesEvent event) {
        enqueue(() -> listener.onLeiosVotes(event));
    }

    @Override
    public void onEndorserBlockUnavailable(EndorserBlockUnavailableEvent event) {
        enqueue(() -> listener.onEndorserBlockUnavailable(event));
    }

    @Override
    public void batchStarted() {
        enqueue(listener::batchStarted);
    }

    @Override
    public void batchDone() {
        enqueue(listener::batchDone);
    }

    @Override
    public void noBlockFound(Point from, Point to) {
        enqueue(() -> listener.noBlockFound(from, to));
    }

    @Override
    public void intersactFound(Tip tip, Point point) {
        startPoint = point; //the next block's parent: lets a certifying first block skip the parent search
        enqueue(() -> listener.intersactFound(tip, point));
    }

    @Override
    public void intersactNotFound(Tip tip) {
        enqueue(() -> listener.intersactNotFound(tip));
    }

    @Override
    public void onDisconnect() {
        enqueue(listener::onDisconnect);
    }

    @Override
    public void onParsingError(BlockParseRuntimeException e) {
        enqueue(() -> listener.onParsingError(e));
    }

    @Override
    public void rollforward(Tip tip, BlockHeader blockHeader, byte[] originalHeaderBytes) {
        enqueue(() -> listener.rollforward(tip, blockHeader, originalHeaderBytes));
    }

    @Override
    public void rollforwardByronEra(Tip tip, ByronBlockHead byronBlockHead, byte[] originalHeaderBytes) {
        enqueue(() -> listener.rollforwardByronEra(tip, byronBlockHead, originalHeaderBytes));
    }

    @Override
    public void rollforwardByronEra(Tip tip, ByronEbHead byronEbHead, byte[] originalHeaderBytes) {
        enqueue(() -> listener.rollforwardByronEra(tip, byronEbHead, originalHeaderBytes));
    }

    @Override
    public void close() {
        closed = true;
        queue.clear(); //queued events are dropped, as a stopped connection drops undelivered ones today
        Event waiting = current;
        if (waiting instanceof CertifyingBlock certifying) {
            certifying.wake.complete(null); //stop waiting for its EB; the worker never interrupts application code
        }
        queue.offer(STOP);
        resolver.close();
    }

    private void enqueue(Event event) {
        try {
            while (!closed && !queue.offer(event, 1, TimeUnit.SECONDS)) {
                //queue full: the application is busy or a certifying block waits for its EB; wait (back-pressure)
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void deliverLoop() {
        while (!closed) {
            Event event;
            try {
                event = queue.take();
            } catch (InterruptedException e) {
                return;
            }
            if (closed) {
                return;
            }
            current = event;
            try {
                event.deliver();
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                log.error("Listener callback failed", e);
            } finally {
                current = null;
            }
        }
    }

    /**
     * Delivers a block like {@code BlockfetchAgent} does: an exception thrown by the application's {@code onBlock}
     * is passed to {@code onParsingError}, as before.
     */
    private void deliverBlock(Era era, Block block, List<Transaction> transactions) {
        try {
            listener.onBlock(era, block, transactions);
        } catch (RuntimeException e) {
            log.error("Error in processing block", e);
            listener.onParsingError(new BlockParseRuntimeException(block.getHeader().getHeaderBody().getBlockNumber(),
                    block.getCbor() != null ? HexUtil.decodeHexString(block.getCbor()) : null, e));
        }
    }

    private synchronized long currentRollbackSequence() {
        return rollbackSequence;
    }

    /** True if a rollback that arrived after {@code arrivalSequence} goes back before {@code slot}. */
    private synchronized boolean rolledBackSince(long arrivalSequence, long slot) {
        for (long[] rollback : rollbacks) {
            if (rollback[0] > arrivalSequence && rollback[1] < slot) {
                return true;
            }
        }
        return false;
    }

    private static void abandonAfter(Event event, Point rollbackPoint) {
        if (event instanceof CertifyingBlock certifying
                && certifying.block.getHeader().getHeaderBody().getSlot() > rollbackPoint.getSlot()) {
            certifying.abandoned = true; //rolled back next: no need to wait for its EB
            certifying.wake.complete(null);
        }
    }

    private synchronized void remember(String blockHash, Announced announced) {
        if (blockHash != null) {
            announcements.put(blockHash, announced);
        }
    }

    private synchronized Announced lookup(String blockHash) {
        return blockHash != null ? announcements.get(blockHash) : null;
    }

    private interface Event {
        void deliver() throws InterruptedException;
    }

    private static final Event STOP = () -> {
    };

    /** Fetches the transactions of a certified endorser block, in EB order ({@link EndorserBlockResolver}). */
    interface EndorserBlockSource extends AutoCloseable {
        CompletableFuture<List<byte[]>> resolve(LeiosPoint point);

        @Override
        void close();
    }

    /** Fetches a block by hash, given its child's slot ({@link ParentBlockLookup}). */
    @FunctionalInterface
    interface ParentBlockSource {
        Block fetch(String blockHash, long childSlot, Point knownPoint) throws Exception;
    }

    /** The EB a ranking block announced, at the announcing block's slot (which is the EB's slot). */
    private record Announced(long slot, LeiosAnnouncement announcement) {
    }

    private final class CertifyingBlock implements Event {
        private final Era era;
        private final Block block;
        private final List<Transaction> transactions;
        private LeiosPoint ebPoint;
        private LeiosEndorserBlockRef ebRef;
        private CompletableFuture<List<byte[]>> pending;
        private volatile boolean abandoned;
        /** Completed on rollback, to stop waiting at once. */
        private final CompletableFuture<Void> wake = new CompletableFuture<>();

        private final long arrivalRollbackSequence;

        private CertifyingBlock(Era era, Block block, List<Transaction> transactions, long arrivalRollbackSequence) {
            this.era = era;
            this.block = block;
            this.transactions = transactions;
            this.arrivalRollbackSequence = arrivalRollbackSequence;
        }

        private boolean isAbandoned() {
            return abandoned || rolledBackSince(arrivalRollbackSequence, block.getHeader().getHeaderBody().getSlot());
        }

        private void use(Announced parent) {
            String ebHash = parent.announcement().getEbHash();
            this.ebPoint = new LeiosPoint(parent.slot(), HexUtil.decodeHexString(ebHash));
            this.ebRef = LeiosEndorserBlockRef.builder()
                    .ebHash(ebHash)
                    .ebSlot(parent.slot())
                    .ebSize(parent.announcement().getEbSize())
                    .build();
        }

        @Override
        public void deliver() throws InterruptedException {
            HeaderBody header = block.getHeader().getHeaderBody();
            for (int attempt = 1; !closed; attempt++) {
                if (isAbandoned()) {
                    deliverBlock(era, block, transactions); //rolled back next; delivered as on the wire
                    return;
                }
                try {
                    if (ebPoint == null) {
                        Block parent = parentLookup.fetch(header.getPrevHash(), header.getSlot(), startPoint);
                        HeaderBody parentHeader = parent.getHeader().getHeaderBody();
                        if (parentHeader.getLeiosAnnouncement() == null) {
                            throw new IllegalStateException("Block " + header.getBlockNumber()
                                    + " is certified but its parent announced no endorser block");
                        }
                        Announced announced = new Announced(parentHeader.getSlot(), parentHeader.getLeiosAnnouncement());
                        remember(parentHeader.getBlockHash(), announced);
                        use(announced);
                    }
                    if (pending == null || pending.isCompletedExceptionally()) {
                        pending = resolver.resolve(ebPoint);
                    }
                    try {
                        CompletableFuture.anyOf(pending, wake).get(EB_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    } catch (java.util.concurrent.TimeoutException e) {
                        pending.cancel(false); //the next attempt sends a new request
                        throw e;
                    }
                    if (closed) {
                        return;
                    }
                    if (isAbandoned()) {
                        continue;
                    }
                    List<byte[]> ebTransactions = pending.get();
                    Block complete = DijkstraBlockBodySerializer.INSTANCE.mergeEndorserBlockTransactions(
                            block, ebRef, ebTransactions);
                    deliverBlock(era, complete, TransactionListBuilder.build(complete));
                    return;
                } catch (InterruptedException e) {
                    throw e;
                } catch (Exception e) {
                    pending = null;
                    boolean giveUp = skipUnavailable && attempt >= SKIP_AFTER_ATTEMPTS;
                    String reason = e.getCause() != null ? e.getCause().toString() : e.toString();
                    log.warn("Endorser block {} for block {} not available (attempt {}): {}",
                            ebRef != null ? ebRef.getEbHash() : "(unknown)", header.getBlockNumber(), attempt, reason);
                    try {
                        listener.onEndorserBlockUnavailable(EndorserBlockUnavailableEvent.builder()
                                .endorserBlock(ebPoint)
                                .certifyingBlockNumber(header.getBlockNumber())
                                .certifyingSlot(header.getSlot())
                                .certifyingBlockHash(header.getBlockHash())
                                .reason(reason)
                                .attempt(attempt)
                                .willRetry(!giveUp)
                                .build());
                    } catch (RuntimeException listenerError) {
                        log.error("onEndorserBlockUnavailable failed", listenerError);
                    }
                    if (giveUp) {
                        deliverBlock(era, block, transactions);
                        return;
                    }
                    pause(Math.min(MAX_RETRY_DELAY_MILLIS, retryBaseMillis << Math.min(attempt, 5)));
                }
            }
        }

        /** Waits before the next attempt; a rollback ends the wait early. */
        private void pause(long millis) throws InterruptedException {
            try {
                wake.get(millis, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException e) {
                //normal end of the pause
            }
        }
    }
}
