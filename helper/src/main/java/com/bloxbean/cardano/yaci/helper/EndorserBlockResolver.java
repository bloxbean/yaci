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
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosPoint;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosRawCbor;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosTxBitmap;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.LeiosFetchAgent;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.LeiosFetchAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosBlockTxs;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Completes a certifying block with the transactions of the endorser block (EB) it certifies, fetched over
 * LeiosFetch on a side connection to the same peer (ADR 0016 D3a, D4a). One resolver belongs to one sync start.
 * <ul>
 *   <li>The side connection carries the handshake and {@code LeiosFetchAgent} only. Its handshake is accepted only
 *       for a version this side offered, V15 or later, not app-layer, with the main connection's network magic.</li>
 *   <li>It is reused across attempts, and replaced at the start of an attempt when it is older than 45 s or has been
 *       idle for 30 s. It is closed when an attempt on it fails.</li>
 *   <li>The EB's references and every transaction are checked against the parent's announcement and the
 *       references before they are mapped.</li>
 * </ul>
 */
class EndorserBlockResolver implements CompleteBlockAssembler.EndorserBlockSource {
    static final long MAX_AGE_MILLIS = 45_000;
    static final long MAX_IDLE_MILLIS = 30_000;
    static final long HANDSHAKE_CAP_MILLIS = 10_000;
    /** The longest wait for one reply of the parent lookup (FindIntersect, BlockFetch). */
    static final long REPLY_CAP_MILLIS = 15_000;
    /**
     * The longest an EB or transactions reply may go with nothing received: a stuck connection is replaced, not waited
     * out, while a slow reply that keeps arriving may use the rest of the attempt's deadline.
     */
    static final long STALL_MILLIS = 15_000;

    private final String host;
    private final int port;
    private final LongSupplier mainMagic;
    private final EbAnnouncementCache cache;
    private final ParentBlockLookup parentLookup;
    private final long maxAgeMillis;
    private final long maxIdleMillis;
    private final long stallMillis;
    private volatile Side side;

    EndorserBlockResolver(String host, int port, LongSupplier mainMagic, EbAnnouncementCache cache,
                          ParentBlockLookup parentLookup) {
        this(host, port, mainMagic, cache, parentLookup, MAX_AGE_MILLIS, MAX_IDLE_MILLIS, STALL_MILLIS);
    }

    EndorserBlockResolver(String host, int port, LongSupplier mainMagic, EbAnnouncementCache cache,
                          ParentBlockLookup parentLookup, long maxAgeMillis, long maxIdleMillis, long stallMillis) {
        this.host = host;
        this.port = port;
        this.mainMagic = mainMagic;
        this.cache = cache;
        this.parentLookup = parentLookup;
        this.maxAgeMillis = maxAgeMillis;
        this.maxIdleMillis = maxIdleMillis;
        this.stallMillis = stallMillis;
    }

    /**
     * One attempt: the parent's announcement (cache, else {@link ParentBlockLookup}), the EB and its transactions,
     * then the mapping, all within {@code deadline}.
     */
    @Override
    public synchronized Block complete(Block certifying, Point knownParent, AttemptDeadline deadline)
            throws Exception {
        HeaderBody header = certifying.getHeader().getHeaderBody();
        EbAnnouncementCache.Announced parent = cache.get(header.getPrevHash());
        if (parent == null) {
            parent = parentLookup.find(header.getPrevHash(), header.getSlot(), knownParent, deadline);
            cache.put(header.getPrevHash(), parent.slot(), parent.announcement());
        }
        EbAnnouncement announcement = parent.announcement();
        if (announcement == null || announcement.getHash() == null)
            throw new IllegalStateException("The parent " + header.getPrevHash() + " announced no endorser block");

        LeiosPoint point = new LeiosPoint(parent.slot(), HexUtil.decodeHexString(announcement.getHash()));
        Side current = sideFor(deadline);
        try {
            List<byte[]> transactions = fetch(current, point, announcement, deadline);
            LeiosEndorserBlockRef ref = LeiosEndorserBlockRef.builder()
                    .ebHash(announcement.getHash())
                    .ebSlot(parent.slot())
                    .ebSize(announcement.getSize())
                    .build();
            Block complete = BlockSerializer.withEndorserBlockTxs(certifying, ref, transactions);
            deadline.check("while mapping the endorser block transactions");
            current.lastUsedNanos = System.nanoTime();
            return complete;
        } catch (Exception e) {
            close(current, e); //a failed attempt, a mapping failure included, never leaves its connection open
            throw e;
        }
    }

    private List<byte[]> fetch(Side current, LeiosPoint point, EbAnnouncement announcement, AttemptDeadline deadline)
            throws Exception {
        deadline.await(current.ready, current.closed, "handshake", HANDSHAKE_CAP_MILLIS);
        CompletableFuture<LeiosRawCbor> ebReply = current.expectBlock();
        current.fetchAgent.requestBlock(point);
        byte[] references = deadline.awaitWhileReceiving(ebReply, current.closed, "endorser block", stallMillis,
                current.fetchAgent::getLastSegmentNanos).getCbor();
        List<RawEbReferences.Entry> entries = checkEndorserBlock(references, announcement, deadline);
        current.clearReplies();
        if (entries.isEmpty())
            return List.of(); //an empty EB: nothing to request, the block is delivered with no EB transactions

        CompletableFuture<LeiosRawCbor> txsReply = current.expectTransactions();
        current.fetchAgent.requestBlockTxs(point, LeiosTxBitmap.firstN(entries.size()));
        LeiosRawCbor txList = deadline.awaitWhileReceiving(txsReply, current.closed, "endorser block transactions",
                stallMillis, current.fetchAgent::getLastSegmentNanos);
        current.clearReplies(); //an idle side connection holds no payload
        return checkTransactions(new MsgLeiosBlockTxs(txList).getTransactions(), entries);
    }

    /** Closes the side connection, if any, without waiting. */
    @Override
    public void close() {
        Side current = side;
        if (current != null)
            close(current, new IllegalStateException("The side connection was closed"));
    }

    /** The current side connection, for tests. */
    Side currentSide() {
        return side;
    }

    private Side sideFor(AttemptDeadline deadline) {
        long magic = mainMagic.getAsLong();
        Side current = side;
        if (current != null) {
            long now = System.nanoTime();
            if (current.closed.isDone() || current.magic != magic
                    || now - current.createdNanos > TimeUnit.MILLISECONDS.toNanos(maxAgeMillis)
                    || now - current.lastUsedNanos >= TimeUnit.MILLISECONDS.toNanos(maxIdleMillis)) {
                close(current, new IllegalStateException("The side connection was replaced"));
                current = null;
            }
        }
        if (current == null) {
            current = new Side(magic, deadline.remainingMillis());
            side = current;
            current.start();
        }
        return current;
    }

    private void close(Side closing, Throwable reason) {
        if (side == closing)
            side = null;
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
        if (transactions.size() != entries.size())
            throw new IllegalStateException("Expected " + entries.size() + " endorser block transactions, received "
                    + transactions.size());
        for (int i = 0; i < transactions.size(); i++) {
            byte[] tx = transactions.get(i);
            RawEbReferences.Entry entry = entries.get(i);
            if (tx.length != entry.size() || !Arrays.equals(Blake2bUtil.blake2bHash256(tx), entry.txHash()))
                throw new IllegalStateException("Endorser block transaction " + i + " does not match its entry");
        }
        return transactions;
    }

    /**
     * One side connection. Its callbacks complete only its own futures, so callbacks of an earlier connection never
     * reach a later attempt. {@link #closed} completes, exceptionally and once, when the connection ends for any
     * reason: the first reason wins.
     */
    final class Side {
        final long magic;
        final long createdNanos = System.nanoTime();
        volatile long lastUsedNanos = createdNanos;
        final VersionTable offered;
        final HandshakeAgent handshakeAgent;
        final LeiosFetchAgent fetchAgent;
        final TCPNodeClient client;
        final CompletableFuture<AcceptVersion> ready = new CompletableFuture<>();
        final CompletableFuture<Void> closed = new CompletableFuture<>();
        private final AtomicBoolean retired = new AtomicBoolean();
        private volatile boolean starting;
        private volatile boolean handshakeDone;
        private volatile CompletableFuture<LeiosRawCbor> blockReply = new CompletableFuture<>();
        private volatile CompletableFuture<LeiosRawCbor> txsReply = new CompletableFuture<>();

        private Side(long magic, long connectTimeoutMillis) {
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
            this.client = new TCPNodeClient(host, port, config, handshakeAgent, fetchAgent);

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
            closed.completeExceptionally(reason);
        }

        private void close(Throwable reason) {
            closed.completeExceptionally(reason);
            if (retired.compareAndSet(false, true))
                ClientRetirement.retireAsync(client, client::shutdown, client::isRunning, () -> starting);
        }
    }
}
