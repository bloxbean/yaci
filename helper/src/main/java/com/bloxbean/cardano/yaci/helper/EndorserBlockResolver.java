package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.transaction.raw.RawEbReferences;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.EbAnnouncement;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.model.LeiosEndorserBlockRef;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.network.NodeClientConfig;
import com.bloxbean.cardano.yaci.core.network.TCPNodeClient;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.Reason;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.core.protocol.keepalive.KeepAliveAgent;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosPoint;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosRawCbor;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosTxBitmap;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.LeiosFetchAgent;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.LeiosFetchAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosBlockTxs;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import lombok.extern.slf4j.Slf4j;

/**
 * Completes a certifying block with the transactions of the endorser block (EB) it certifies, fetched over
 * LeiosFetch on a side connection to the same peer (ADR 0016 D3a, D4a). One resolver belongs to one sync start.
 * <ul>
 *   <li>The side connection dials the address the main connection is connected to, not the configured host name,
 *       which may resolve to other relays. It is replaced when the main connection's address changes.</li>
 *   <li>The transactions are requested in consecutive batches of at most {@link #MAX_TXS_REQUEST_BYTES} transaction
 *       bytes, one request at a time, each reply checked against its own entries.</li>
 *   <li>The side connection carries the handshake, {@code LeiosFetchAgent} and {@code KeepAliveAgent}. Its handshake is accepted only
 *       for a version this side offered, V15 or later, not app-layer, with the main connection's network magic.</li>
 *   <li>It is kept alive (a keep-alive every {@link #KEEP_ALIVE_MILLIS}, at most one in flight) and reused for the
 *       whole start (ADR 0017 D2). It closes when a keep-alive goes unanswered for two intervals, when it has been
 *       idle for {@link #MAX_IDLE_MILLIS}, and when an attempt on it fails.</li>
 *   <li>The EB's references and every transaction are checked against the parent's announcement and the
 *       references before they are mapped.</li>
 * </ul>
 */
@Slf4j
class EndorserBlockResolver implements CompleteBlockAssembler.EndorserBlockSource {
    /** How often the side connection sends a keep-alive; one unanswered for two intervals closes it. */
    static final long KEEP_ALIVE_MILLIS = 20_000;
    /** A side connection idle this long closes itself. */
    static final long MAX_IDLE_MILLIS = 300_000;
    static final long HANDSHAKE_CAP_MILLIS = 10_000;
    /** The longest wait for one reply of the parent lookup (FindIntersect, BlockFetch). */
    static final long REPLY_CAP_MILLIS = 15_000;
    /**
     * The longest an EB or transactions reply may go with nothing received: a stuck connection is replaced, not waited
     * out, while a slow reply that keeps arriving may use the rest of the attempt's deadline.
     */
    static final long STALL_MILLIS = 15_000;
    /**
     * The most transaction bytes one transactions request asks for: the protocol's
     * {@code maxLeiosTxsRequestBytesSize}. A Haskell client batches to it, and a peer may refuse a request above it.
     */
    static final long MAX_TXS_REQUEST_BYTES = 500_000;
    /** Runs every side connection's keep-alive timer; a closed connection's timer is cancelled. */
    private static final ScheduledThreadPoolExecutor KEEP_ALIVES = keepAliveScheduler();

    private final Supplier<PeerAddress> peer;
    private final LongSupplier mainMagic;
    private final EbAnnouncementCache cache;
    private final ParentBlockLookup parentLookup;
    private final long keepAliveMillis;
    private final long maxIdleMillis;
    private final long stallMillis;
    private final long maxTxsRequestBytes;
    /** The current side connection; detached by compare-and-set, so a late close cannot clear its replacement. */
    private final AtomicReference<Side> side = new AtomicReference<>();
    /** Runs at the start of each keep-alive tick, before its decision: a test seam, a no-op otherwise. */
    volatile Runnable beforeKeepAliveTick = () -> {
    };
    /** Side connections opened by this resolver (debug log and tests). */
    private final AtomicLong sidesOpened = new AtomicLong();

    /** @param peer where the side connection dials, read for each new connection (the main connection's address) */
    EndorserBlockResolver(Supplier<PeerAddress> peer, LongSupplier mainMagic, EbAnnouncementCache cache,
                          ParentBlockLookup parentLookup) {
        this(peer, mainMagic, cache, parentLookup, KEEP_ALIVE_MILLIS, MAX_IDLE_MILLIS, STALL_MILLIS,
                MAX_TXS_REQUEST_BYTES);
    }

    EndorserBlockResolver(String host, int port, LongSupplier mainMagic, EbAnnouncementCache cache,
                          ParentBlockLookup parentLookup, long keepAliveMillis, long maxIdleMillis, long stallMillis) {
        this(() -> new PeerAddress(host, port), mainMagic, cache, parentLookup, keepAliveMillis, maxIdleMillis,
                stallMillis, MAX_TXS_REQUEST_BYTES);
    }

    EndorserBlockResolver(Supplier<PeerAddress> peer, LongSupplier mainMagic, EbAnnouncementCache cache,
                          ParentBlockLookup parentLookup, long keepAliveMillis, long maxIdleMillis, long stallMillis,
                          long maxTxsRequestBytes) {
        this.peer = peer;
        this.mainMagic = mainMagic;
        this.cache = cache;
        this.parentLookup = parentLookup;
        this.keepAliveMillis = keepAliveMillis;
        this.maxIdleMillis = maxIdleMillis;
        this.stallMillis = stallMillis;
        this.maxTxsRequestBytes = maxTxsRequestBytes;
    }

    /**
     * One attempt: the parent's announcement (cache, else {@link ParentBlockLookup}), the EB and its transactions,
     * then the mapping, all within {@code deadline}.
     */
    @Override
    public synchronized Block complete(Block certifying, Point knownParent, AttemptDeadline deadline)
            throws Exception {
        HeaderBody header = certifying.getHeader().getHeaderBody();
        Timings timings = new Timings();
        long started = System.nanoTime();
        EbAnnouncementCache.Announced parent = cache.get(header.getPrevHash());
        if (parent == null) {
            parent = parentLookup.find(header.getPrevHash(), header.getSlot(), knownParent, deadline);
            cache.put(header.getPrevHash(), parent.slot(), parent.announcement());
            timings.parentLookupNanos = System.nanoTime() - started;
        }
        EbAnnouncement announcement = parent.announcement();
        if (announcement == null || announcement.getHash() == null)
            throw new IllegalStateException("The parent " + header.getPrevHash() + " announced no endorser block");

        LeiosPoint point = new LeiosPoint(parent.slot(), HexUtil.decodeHexString(announcement.getHash()));
        Side current = sideFor(deadline, timings);
        try {
            List<byte[]> transactions = fetch(current, point, announcement, deadline, timings);
            long mapping = System.nanoTime();
            LeiosEndorserBlockRef ref = LeiosEndorserBlockRef.builder()
                    .ebHash(announcement.getHash())
                    .ebSlot(parent.slot())
                    .ebSize(announcement.getSize())
                    .build();
            Block complete = BlockSerializer.withEndorserBlockTxs(certifying, ref, transactions);
            deadline.check("while mapping the endorser block transactions");
            current.lastUsedNanos = System.nanoTime();
            timings.mapNanos = current.lastUsedNanos - mapping;
            if (log.isDebugEnabled())
                log.debug("Block {} EB {}: {} txs in {} batches, {} bytes; {} ms in all: parent lookup {}, side "
                                + "connection #{} {} ready {}, EB {}, txs {}, mapping {} (ms)",
                        header.getBlockNumber(), announcement.getHash().substring(0, 8), transactions.size(),
                        timings.batches, timings.txBytes, millis(current.lastUsedNanos - started),
                        millis(timings.parentLookupNanos), current.number, timings.newSide ? "new" : "reused",
                        millis(timings.readyNanos), millis(timings.ebNanos), millis(timings.txsNanos),
                        millis(timings.mapNanos));
            return complete;
        } catch (Exception e) {
            retire(current, e); //a failed attempt, a mapping failure included, never leaves its connection open
            throw e;
        }
    }

    private List<byte[]> fetch(Side current, LeiosPoint point, EbAnnouncement announcement, AttemptDeadline deadline,
                               Timings timings) throws Exception {
        long phase = System.nanoTime();
        deadline.await(current.ready, current.closed, "handshake", HANDSHAKE_CAP_MILLIS);
        long now = System.nanoTime();
        timings.readyNanos = now - phase;
        phase = now;
        CompletableFuture<LeiosRawCbor> ebReply = current.expectBlock();
        current.fetchAgent.requestBlock(point);
        byte[] references = deadline.awaitWhileReceiving(ebReply, current.closed, "endorser block", stallMillis,
                current.fetchAgent::getLastSegmentNanos).getCbor();
        List<RawEbReferences.Entry> entries = checkEndorserBlock(references, announcement, deadline);
        current.clearReplies();
        now = System.nanoTime();
        timings.ebNanos = now - phase;
        phase = now;
        if (entries.isEmpty())
            return List.of(); //an empty EB: nothing to request, the block is delivered with no EB transactions

        //one request per batch, in EB order, each checked against its own entries
        List<byte[]> transactions = new ArrayList<>(entries.size());
        for (int[] batch : batches(entries, maxTxsRequestBytes)) {
            deadline.check("before requesting endorser block transactions from index " + batch[0]);
            CompletableFuture<LeiosRawCbor> txsReply = current.expectTransactions();
            current.fetchAgent.requestBlockTxs(point, range(batch[0], batch[1]));
            LeiosRawCbor txList = deadline.awaitWhileReceiving(txsReply, current.closed,
                    "endorser block transactions", stallMillis, current.fetchAgent::getLastSegmentNanos);
            current.clearReplies(); //an idle side connection holds no payload
            transactions.addAll(checkTransactions(new MsgLeiosBlockTxs(txList).getTransactions(),
                    entries.subList(batch[0], batch[1]), batch[0]));
            timings.batches++;
        }
        timings.txsNanos = System.nanoTime() - phase;
        for (byte[] tx : transactions)
            timings.txBytes += tx.length;
        return transactions;
    }

    /** Where one attempt's time went, for the debug log. */
    private static final class Timings {
        long parentLookupNanos;
        boolean newSide;
        long readyNanos;
        long ebNanos;
        long txsNanos;
        long mapNanos;
        int batches;
        long txBytes;
    }

    private static ScheduledThreadPoolExecutor keepAliveScheduler() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1,
                task -> {
                    Thread thread = new Thread(task, "yaci-leios-keepalive");
                    thread.setDaemon(true);
                    return thread;
                });
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    private static long millis(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    /** The side connections this resolver has opened, for tests. */
    long sidesOpened() {
        return sidesOpened.get();
    }

    /**
     * The entries split into consecutive batches {@code [from, to)} whose transactions add up to at most
     * {@code maxBytes}. An entry larger than {@code maxBytes} cannot be requested within the ceiling: the EB is
     * rejected before anything is requested.
     */
    static List<int[]> batches(List<RawEbReferences.Entry> entries, long maxBytes) {
        List<int[]> batches = new ArrayList<>();
        int from = 0;
        long bytes = 0;
        for (int i = 0; i < entries.size(); i++) {
            long size = entries.get(i).size();
            if (size > maxBytes)
                throw new IllegalStateException("Endorser block transaction " + i + " declares " + size
                        + " bytes, above the " + maxBytes + "-byte request ceiling");
            if (i > from && bytes + size > maxBytes) {
                batches.add(new int[]{from, i});
                from = i;
                bytes = 0;
            }
            bytes += size;
        }
        if (from < entries.size())
            batches.add(new int[]{from, entries.size()});
        return batches;
    }

    /** The bitmap of the transactions {@code [from, to)}. */
    static LeiosTxBitmap range(int from, int to) {
        return LeiosTxBitmap.fromIndices(IntStream.range(from, to).toArray());
    }

    /** Closes the side connection, if any, without waiting. */
    @Override
    public void close() {
        Side current = side.get();
        if (current != null)
            retire(current, new IllegalStateException("The side connection was closed"));
    }

    /** The current side connection, for tests. */
    Side currentSide() {
        return side.get();
    }

    private Side sideFor(AttemptDeadline deadline, Timings timings) {
        long magic = mainMagic.getAsLong();
        PeerAddress address = peer.get();
        Side current = side.get();
        if (current != null) {
            long now = System.nanoTime();
            String why = current.closed.isDone() ? "closed" : current.magic != magic ? "network magic changed"
                    : !current.address.equals(address) ? "relay address changed"
                    : now - current.lastUsedNanos >= TimeUnit.MILLISECONDS.toNanos(maxIdleMillis) ? "idle for "
                    + maxIdleMillis + " ms" : null;
            if (why != null) {
                log.debug("Side connection #{} replaced: {}", current.number, why);
                retire(current, new IllegalStateException("The side connection was replaced"));
                current = null;
            }
        }
        if (current == null) {
            current = new Side(address, magic, deadline.remainingMillis());
            side.set(current);
            current.start();
            timings.newSide = true;
            log.debug("Side connection #{} opened to {}", current.number, address);
        }
        current.lastUsedNanos = System.nanoTime(); //in use: the idle timer does not close it under an attempt
        return current;
    }

    /** Closes a side connection, and detaches it only if it is still the current one (I4). */
    void retire(Side closing, Throwable reason) {
        side.compareAndSet(closing, null);
        closing.close(reason);
    }

    /** D3a: the references as received hash to the announced hash and have the announced size; no entry repeats. */
    static List<RawEbReferences.Entry> checkEndorserBlock(byte[] references, EbAnnouncement announcement,
                                                         AttemptDeadline deadline) throws TimeoutException {
        if (references.length != announcement.getSize())
            throw new IllegalStateException("Endorser block size " + references.length + " is not the announced "
                    + announcement.getSize());
        String hash = HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(references));
        if (!hash.equals(announcement.getHash()))
            throw new IllegalStateException("Endorser block hash " + hash + " is not the announced "
                    + announcement.getHash());
        List<RawEbReferences.Entry> entries = RawEbReferences.of(references).entries();
        Set<String> seen = new HashSet<>();
        for (RawEbReferences.Entry entry : entries) {
            if (entry.txHash() == null || entry.txHash().length != 32)
                throw new IllegalStateException("An endorser block entry's hash is not 32 bytes");
            if (!seen.add(HexUtil.encodeHexString(entry.txHash())))
                throw new IllegalStateException("The endorser block lists transaction "
                        + HexUtil.encodeHexString(entry.txHash()) + " twice");
        }
        deadline.check("while checking the endorser block"); //a large EB takes time to check
        return entries;
    }

    /** D3a: one transaction per entry, in entry order, each hashing to its entry with its entry's length. */
    static List<byte[]> checkTransactions(List<byte[]> transactions, List<RawEbReferences.Entry> entries) {
        return checkTransactions(transactions, entries, 0);
    }

    /** As {@link #checkTransactions(List, List)}, for the batch of entries that starts at EB index {@code first}. */
    static List<byte[]> checkTransactions(List<byte[]> transactions, List<RawEbReferences.Entry> entries, int first) {
        if (transactions.size() != entries.size())
            throw new IllegalStateException("Expected " + entries.size() + " endorser block transactions from index "
                    + first + ", received " + transactions.size());
        for (int i = 0; i < transactions.size(); i++) {
            byte[] tx = transactions.get(i);
            RawEbReferences.Entry entry = entries.get(i);
            if (tx.length != entry.size() || !Arrays.equals(Blake2bUtil.blake2bHash256(tx), entry.txHash()))
                throw new IllegalStateException("Endorser block transaction " + (first + i)
                        + " does not match its entry");
        }
        return transactions;
    }

    /**
     * One side connection. Its callbacks complete only its own futures, so callbacks of an earlier connection never
     * reach a later attempt. {@link #closed} completes, exceptionally and once, when the connection ends for any
     * reason: the first reason wins.
     */
    final class Side {
        final PeerAddress address;
        final long magic;
        final long number = sidesOpened.incrementAndGet();
        final long createdNanos = System.nanoTime();
        volatile long lastUsedNanos = createdNanos;
        final VersionTable offered;
        final HandshakeAgent handshakeAgent;
        final LeiosFetchAgent fetchAgent;
        final KeepAliveAgent keepAliveAgent = new KeepAliveAgent();
        final TCPNodeClient client;
        private volatile ScheduledFuture<?> keepAliveTimer;
        /** Serializes a keep-alive tick with fail and close, so a closed connection sends nothing (I3). */
        private final Object lifecycle = new Object();
        /** When the keep-alive in flight was sent, or 0 when none is (I2). */
        private volatile long keepAliveSentNanos;
        private int cookie;
        final CompletableFuture<AcceptVersion> ready = new CompletableFuture<>();
        final CompletableFuture<Void> closed = new CompletableFuture<>();
        private final AtomicBoolean retired = new AtomicBoolean();
        private volatile boolean starting;
        private volatile boolean handshakeDone;
        private volatile CompletableFuture<LeiosRawCbor> blockReply = new CompletableFuture<>();
        private volatile CompletableFuture<LeiosRawCbor> txsReply = new CompletableFuture<>();

        private Side(PeerAddress address, long magic, long connectTimeoutMillis) {
            this.address = address;
            this.magic = magic;
            this.offered = N2NVersionTableConstant.v11AndAbove(magic);
            this.handshakeAgent = new HandshakeAgent(offered);
            this.handshakeAgent.setSuppressConnectionInfoLog(true);
            this.fetchAgent = new LeiosFetchAgent();
            NodeClientConfig config = NodeClientConfig.builder()
                    .autoReconnect(false)
                    .propagateStartupFailure(true)
                    .connectionTimeoutMs((int) Math.max(1, Math.min(Integer.MAX_VALUE, connectTimeoutMillis)))
                    .enableConnectionLogging(false)
                    .build();
            this.client = new TCPNodeClient(address.host(), address.port(), config, handshakeAgent, fetchAgent,
                    keepAliveAgent);
            keepAliveAgent.addListener(response -> keepAliveSentNanos = 0);

            handshakeAgent.addListener(new HandshakeAgentListener() {
                @Override
                public void handshakeOk() {
                    AcceptVersion accepted = handshakeAgent.getProtocolVersion();
                    String problem = acceptProblem(accepted);
                    if (problem != null) {
                        fail(new IllegalStateException(problem));
                    } else {
                        handshakeDone = true;
                        ready.complete(accepted);
                        startKeepAlive();
                    }
                }

                @Override
                public void handshakeError(Reason reason) {
                    fail(new IllegalStateException("The peer refused the handshake: " + reason));
                }
            });
            fetchAgent.addListener(new LeiosFetchAgentListener() {
                @Override
                public void onBlock(LeiosPoint requestedPoint, LeiosRawCbor endorserBlock) {
                    blockReply.complete(endorserBlock);
                }

                @Override
                public void onBlockTxs(LeiosPoint requestedPoint, LeiosTxBitmap requestedBitmap, LeiosRawCbor txList) {
                    txsReply.complete(txList);
                }

                @Override
                public void onFetchError(Throwable error) {
                    fail(new IllegalStateException("LeiosFetch failed: " + error, error));
                }

                @Override
                public void onDisconnect() {
                    //Session.start() calls disconnected() on every agent before it connects: not a loss
                    if (handshakeDone)
                        fail(new IllegalStateException("The connection was closed"));
                }
            });
        }

        String acceptProblem(AcceptVersion accepted) {
            if (accepted == null || accepted.getVersionData() == null)
                return "The handshake accepted no version";
            long version = accepted.getVersionNumber();
            if (!offered.getVersionDataMap().containsKey(version))
                return "The peer accepted version " + version + ", which was not offered";
            if (version < N2NVersionTableConstant.PROTOCOL_V15)
                return "The peer accepted version " + version + ", before V15";
            if (N2NVersionTableConstant.isAppLayerVersion(version))
                return "The peer accepted app-layer version " + version;
            if (accepted.getVersionData().getNetworkMagic() != magic)
                return "The peer accepted network magic " + accepted.getVersionData().getNetworkMagic()
                        + ", not the main connection's " + magic;
            return null;
        }

        private void start() {
            starting = true;
            Thread thread = new Thread(() -> {
                try {
                    client.start(); //connects, then waits for the handshake; ends when the client is shut down
                } catch (Throwable e) {
                    fail(new IllegalStateException("Could not connect: " + e.getMessage(), e));
                } finally {
                    starting = false;
                }
                if (closed.isDone()) //connected after its close: shut down once; close() scheduled the rechecks
                    ClientRetirement.shutdownNow(client, client::shutdown);
            }, "yaci-leios-eb-connect");
            thread.setDaemon(true);
            thread.start();
        }

        private CompletableFuture<LeiosRawCbor> expectBlock() {
            CompletableFuture<LeiosRawCbor> reply = new CompletableFuture<>();
            blockReply = reply;
            return reply;
        }

        private void clearReplies() {
            blockReply = new CompletableFuture<>();
            txsReply = new CompletableFuture<>();
        }

        private CompletableFuture<LeiosRawCbor> expectTransactions() {
            CompletableFuture<LeiosRawCbor> reply = new CompletableFuture<>();
            txsReply = reply;
            return reply;
        }

        /** The connection ended (refused, lost or failed): the first reason is kept. */
        private void fail(Throwable reason) {
            synchronized (lifecycle) {
                closed.completeExceptionally(reason);
                stopKeepAlive();
            }
        }

        private void close(Throwable reason) {
            synchronized (lifecycle) {
                closed.completeExceptionally(reason);
                stopKeepAlive();
            }
            if (retired.compareAndSet(false, true))
                ClientRetirement.retireAsync(client, client::shutdown, client::isRunning, () -> starting);
        }

        private void startKeepAlive() {
            synchronized (lifecycle) {
                if (!closed.isDone())
                    keepAliveTimer = KEEP_ALIVES.scheduleWithFixedDelay(this::keepAlive, keepAliveMillis,
                            keepAliveMillis, TimeUnit.MILLISECONDS);
            }
        }

        private void stopKeepAlive() {
            ScheduledFuture<?> timer = keepAliveTimer;
            if (timer != null)
                timer.cancel(false);
        }

        /** One tick: close if unanswered for two intervals or idle too long, else send one if none is in flight. */
        private void keepAlive() {
            try {
                tick();
            } catch (Throwable e) {
                //an exception would end the timer silently (scheduleWithFixedDelay): close instead of going quiet
                log.warn("Side connection #{}: keep-alive tick failed, closing", number, e);
                retire(this, new IllegalStateException("The keep-alive tick failed: " + e, e));
            }
        }

        private void tick() {
            beforeKeepAliveTick.run();
            synchronized (lifecycle) {
                if (closed.isDone()) {
                    stopKeepAlive();
                    return;
                }
                long now = System.nanoTime();
                long sent = keepAliveSentNanos;
                if (sent != 0 && now - sent >= TimeUnit.MILLISECONDS.toNanos(2 * keepAliveMillis)) {
                    log.debug("Side connection #{}: keep-alive unanswered for {} ms, closing", number,
                            millis(now - sent));
                    retire(this, new IllegalStateException("The keep-alive went unanswered"));
                } else if (now - lastUsedNanos >= TimeUnit.MILLISECONDS.toNanos(maxIdleMillis)) {
                    log.debug("Side connection #{}: idle for {} ms, closing", number, millis(now - lastUsedNanos));
                    retire(this, new IllegalStateException("The side connection was idle"));
                } else if (sent == 0) {
                    keepAliveSentNanos = now;
                    keepAliveAgent.sendKeepAlive(cookie);
                    cookie = (cookie + 1) & KeepAliveAgent.MAX_NUM;
                }
            }
        }
    }
}
