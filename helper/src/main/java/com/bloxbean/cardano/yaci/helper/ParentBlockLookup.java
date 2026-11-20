package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.exception.BlockParseRuntimeException;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.network.NodeClientConfig;
import com.bloxbean.cardano.yaci.core.network.TCPNodeClient;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgent;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.FindIntersect;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainSyncAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainsyncAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.Reason;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;

import java.util.Optional;
import java.util.function.Supplier;
import java.util.concurrent.CompletableFuture;

/**
 * Fetches the parent of a certifying block whose announcement is not in the cache (ADR 0016 D4b), over a
 * short-lived side connection (ChainSync and BlockFetch). The parent is never passed to the sync's listener.
 * <ul>
 *   <li>A known point (the point a tip sync started from) with the parent's hash is fetched directly.</li>
 *   <li>Otherwise a header names its parent's hash but not its slot, so it searches: {@code FindIntersect} with the
 *       candidate points {@code (slot, prevHash)} for the 1,000 slots before the child, then the 1,000 before
 *       those, down to slot 0, until it is found or the attempt's deadline passes.</li>
 * </ul>
 * The fetched block must have the hash asked for.
 */
class ParentBlockLookup {
    static final int WINDOW = 1_000;

    private final Supplier<PeerAddress> peer;
    private final VersionTable versionTable;
    private final long replyCapMillis;

    ParentBlockLookup(String host, int port, VersionTable versionTable) {
        this(() -> new PeerAddress(host, port), versionTable, EndorserBlockResolver.REPLY_CAP_MILLIS);
    }

    /** @param peer where each lookup dials, read per lookup (the main connection's address) */
    ParentBlockLookup(Supplier<PeerAddress> peer, VersionTable versionTable) {
        this(peer, versionTable, EndorserBlockResolver.REPLY_CAP_MILLIS);
    }

    ParentBlockLookup(String host, int port, VersionTable versionTable, long replyCapMillis) {
        this(() -> new PeerAddress(host, port), versionTable, replyCapMillis);
    }

    /** @param replyCapMillis the longest wait for one FindIntersect or block reply */
    ParentBlockLookup(Supplier<PeerAddress> peer, VersionTable versionTable, long replyCapMillis) {
        this.peer = peer;
        this.versionTable = versionTable;
        this.replyCapMillis = replyCapMillis;
    }

    /**
     * @param parentHash the hash of the wanted block
     * @param childSlot  the slot of its child
     * @param knownPoint a point that may be the parent (the point a sync started from), or null
     * @return the parent's slot and announcement
     */
    EbAnnouncementCache.Announced find(String parentHash, long childSlot, Point knownPoint, AttemptDeadline deadline)
            throws Exception {
        Connection connection = new Connection(deadline.remainingMillis());
        try {
            connection.start();
            deadline.await(connection.ready, connection.closed, "handshake for the parent lookup",
                    EndorserBlockResolver.HANDSHAKE_CAP_MILLIS);
            Point point = knownPoint != null && parentHash.equals(knownPoint.getHash())
                    ? knownPoint : search(connection, parentHash, childSlot, deadline);

            Block parent = connection.fetch(point, deadline);
            HeaderBody header = parent.getHeader().getHeaderBody();
            if (!parentHash.equals(header.getBlockHash()))
                throw new IllegalStateException("The peer served block " + header.getBlockHash() + " for the parent "
                        + parentHash);
            return new EbAnnouncementCache.Announced(header.getSlot(), header.getEbAnnouncement());
        } finally {
            connection.closed.completeExceptionally(new IllegalStateException("The parent lookup ended"));
            ClientRetirement.retireAsync(connection.client, connection.client::shutdown,
                    connection.client::isRunning, () -> connection.starting);
        }
    }

    private Point search(Connection connection, String parentHash, long childSlot, AttemptDeadline deadline)
            throws Exception {
        for (long top = childSlot - 1; top >= 0; top -= WINDOW) {
            int count = (int) Math.min(WINDOW, top + 1);
            Point[] window = new Point[count];
            for (int i = 0; i < count; i++)
                window[i] = new Point(top - i, parentHash);
            Optional<Point> found = connection.intersect(window, deadline);
            if (found.isPresent())
                return found.get();
        }
        throw new IllegalStateException("The parent " + parentHash + " was not found before slot " + childSlot);
    }

    /** One short-lived connection: handshake, ChainSync for FindIntersect only, BlockFetch. */
    private final class Connection {
        final HandshakeAgent handshakeAgent = new HandshakeAgent(versionTable);
        final WindowAgent chainSyncAgent = new WindowAgent();
        final BlockfetchAgent blockfetchAgent = new BlockfetchAgent();
        final TCPNodeClient client;
        final CompletableFuture<Void> ready = new CompletableFuture<>();
        /** Completes, exceptionally and once, when the connection ends: the first reason is kept. */
        final CompletableFuture<Void> closed = new CompletableFuture<>();
        volatile boolean handshakeDone;
        volatile boolean starting;
        volatile CompletableFuture<Optional<Point>> intersection = new CompletableFuture<>();
        volatile CompletableFuture<Block> block = new CompletableFuture<>();

        Connection(long connectTimeoutMillis) {
            handshakeAgent.setSuppressConnectionInfoLog(true);
            NodeClientConfig config = NodeClientConfig.builder()
                    .autoReconnect(false)
                    .propagateStartupFailure(true)
                    .connectionTimeoutMs((int) Math.max(1, Math.min(Integer.MAX_VALUE, connectTimeoutMillis)))
                    .enableConnectionLogging(false)
                    .build();
            PeerAddress address = peer.get();
            client = new TCPNodeClient(address.host(), address.port(), config, handshakeAgent, chainSyncAgent,
                    blockfetchAgent);

            handshakeAgent.addListener(new HandshakeAgentListener() {
                @Override
                public void handshakeOk() {
                    handshakeDone = true;
                    ready.complete(null);
                }

                @Override
                public void handshakeError(Reason reason) {
                    fail(new IllegalStateException("The peer refused the handshake: " + reason));
                }
            });
            chainSyncAgent.addListener(new ChainSyncAgentListener() {
                @Override
                public void intersactFound(Tip tip, Point point) {
                    intersection.complete(Optional.of(point));
                }

                @Override
                public void intersactNotFound(Tip tip) {
                    intersection.complete(Optional.empty());
                }

                @Override
                public void onDisconnect() {
                    if (handshakeDone) //Session.start() calls disconnected() before it connects: not a loss
                        fail(new IllegalStateException("The connection was closed"));
                }
            });
            blockfetchAgent.addListener(new BlockfetchAgentListener() {
                @Override
                public void blockFound(Block found) {
                    block.complete(found);
                }

                @Override
                public void noBlockFound(Point from, Point to) {
                    block.completeExceptionally(new IllegalStateException("The peer has no block at " + from));
                }

                @Override
                public void onParsingError(BlockParseRuntimeException e) {
                    block.completeExceptionally(e);
                }
            });
        }

        void start() {
            starting = true;
            Thread thread = new Thread(() -> {
                try {
                    client.start();
                } catch (Throwable e) {
                    fail(new IllegalStateException("Could not connect: " + e.getMessage(), e));
                } finally {
                    starting = false;
                }
                if (closed.isDone()) //connected after its close: shut down once; the close scheduled the rechecks
                    ClientRetirement.shutdownNow(client, client::shutdown);
            }, "yaci-leios-parent-connect");
            thread.setDaemon(true);
            thread.start();
        }

        Optional<Point> intersect(Point[] window, AttemptDeadline deadline) throws Exception {
            CompletableFuture<Optional<Point>> reply = new CompletableFuture<>();
            intersection = reply;
            chainSyncAgent.window = window;
            chainSyncAgent.sendNextMessage();
            return deadline.await(reply, closed, "intersection for the parent lookup", replyCapMillis);
        }

        Block fetch(Point point, AttemptDeadline deadline) throws Exception {
            CompletableFuture<Block> reply = new CompletableFuture<>();
            block = reply;
            blockfetchAgent.resetPoints(point, point);
            blockfetchAgent.sendNextMessage();
            return deadline.await(reply, closed, "parent block", replyCapMillis);
        }

        void fail(Throwable error) {
            closed.completeExceptionally(error);
        }
    }

    /** A ChainSync client that only sends {@code FindIntersect} for the current window. */
    private static final class WindowAgent extends ChainsyncAgent {
        volatile Point[] window;

        WindowAgent() {
            super(new Point[0]);
        }

        @Override
        public Message buildNextMessage() {
            Point[] points = window;
            return points != null ? new FindIntersect(points) : null;
        }
    }
}
