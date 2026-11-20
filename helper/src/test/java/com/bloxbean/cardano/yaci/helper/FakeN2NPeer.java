package com.bloxbean.cardano.yaci.helper;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.messages.MsgBlock;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.IntersectFound;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.IntersectNotFound;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.N2NVersionData;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.ReasonVersionMismatch;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.Refuse;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A node-to-node peer for tests, over a plain socket: the handshake, ChainSync {@code FindIntersect}, BlockFetch
 * ranges, KeepAlive and LeiosFetch, each answered as configured. It records what it receives.
 */
final class FakeN2NPeer implements AutoCloseable {
    static final long MAGIC = 164;

    enum Handshake {ACCEPT, REFUSE, SILENT}

    enum Fetch {ANSWER, SILENT, CLOSE}

    /** One received message: the connection number (from 1), the mini-protocol and its payload. */
    record Received(int connection, int protocol, byte[] payload) {
        int tag() {
            try {
                DataItem item = CborDecoder.decode(payload).get(0);
                return ((UnsignedInteger) ((Array) item).getDataItems().get(0)).getValue().intValue();
            } catch (Exception e) {
                return -1;
            }
        }
    }

    volatile Handshake handshake = Handshake.ACCEPT;
    volatile long acceptVersion = 15;
    volatile long acceptMagic = MAGIC;
    volatile Fetch fetch = Fetch.ANSWER;
    /** The full LeiosFetch replies sent as they are: {@code [2, eb]} and {@code [4, txs]}. */
    volatile byte[] blockReply;
    volatile byte[] txsReply;
    /** If set, every BlockFetch range is answered with this block instead (a wrong block). */
    volatile byte[] servedInstead;
    /** The first this many connections that send FindIntersect get no reply. */
    volatile int silentIntersectConnections;
    private final java.util.Set<Integer> intersectConnections = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** The first this many connections that send a LeiosFetch request get no reply. */
    volatile int silentLeiosFetchConnections;
    private final java.util.Set<Integer> leiosFetchConnections = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** If set, the connection is closed right after the endorser block reply. */
    volatile boolean closeAfterBlockReply;
    /** If set, every FindIntersect is answered IntersectNotFound: the peer cannot find a parent. */
    volatile boolean intersectNotFound;
    /** If set, LeiosFetch replies wait for it. */
    volatile java.util.concurrent.CountDownLatch holdLeiosFetch;
    /** If above 0, LeiosFetch replies are sent in segments of this many bytes, {@link #leiosFetchSegmentDelayMillis} apart. */
    volatile int leiosFetchSegmentBytes;
    volatile long leiosFetchSegmentDelayMillis;
    /** If above 0, only this many segments of a transactions reply are sent, then nothing, the connection left open. */
    volatile int leiosFetchTxsSegmentsThenSilent;
    /** If set, a transactions request is answered with the requested ones of these, in order, instead of txsReply. */
    volatile List<byte[]> ebTransactions;
    /** If above 0, a transactions request for more transaction bytes than this closes the connection (a refusal). */
    volatile long txsRequestLimit;
    /** The transaction bytes each transactions request asked for, when {@link #ebTransactions} is set. */
    final List<Long> txsRequestBytes = new CopyOnWriteArrayList<>();
    /** If above 0, transactions requests after the first this many get no reply (a stall in a later batch). */
    volatile int txsRequestsAnswered;

    //bound to the loopback address the clients dial, never the wildcard: with a wildcard bind, another process (a
    //parallel test run) can bind 127.0.0.1 on the same port and take the clients' connections
    private final ServerSocket server = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
    private final List<Block> chain = new CopyOnWriteArrayList<>();
    private final List<byte[]> chainBytes = new CopyOnWriteArrayList<>();
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    final List<Received> received = new CopyOnWriteArrayList<>();
    final AtomicInteger connections = new AtomicInteger();
    final AtomicInteger openConnections = new AtomicInteger();

    FakeN2NPeer() throws IOException {
        Thread acceptor = new Thread(this::acceptLoop, "fake-n2n-peer");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    int port() {
        return server.getLocalPort();
    }

    /** Adds a block (BlockFetch payload {@code [era, block]}) to the chain this peer serves, in chain order. */
    FakeN2NPeer serve(byte[] blockBytes) {
        chain.add(BlockSerializer.INSTANCE.deserialize(blockBytes));
        chainBytes.add(blockBytes);
        return this;
    }

    List<Received> received(int protocol) {
        return received.stream().filter(r -> r.protocol() == protocol).toList();
    }

    /** Closes every open connection, as a peer that drops them. */
    void dropConnections() throws IOException {
        for (Socket socket : sockets)
            socket.close();
    }

    @Override
    public void close() throws IOException {
        server.close();
        dropConnections();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                sockets.add(socket);
                int number = connections.incrementAndGet();
                Thread reader = new Thread(() -> serve(socket, number), "fake-n2n-peer-" + number);
                reader.setDaemon(true);
                reader.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket socket, int connection) {
        openConnections.incrementAndGet();
        try (socket) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            while (true) {
                in.readInt();
                int protocol = in.readUnsignedShort() & 0x7FFF;
                byte[] payload = new byte[in.readUnsignedShort()];
                in.readFully(payload);
                Received message = new Received(connection, protocol, payload);
                received.add(message);
                if (!answer(message, out))
                    return;
            }
        } catch (EOFException e) {
            //the client closed
        } catch (Exception e) {
            //closed
        } finally {
            openConnections.decrementAndGet();
        }
    }

    /** @return false to close the connection */
    private boolean answer(Received message, OutputStream out) throws Exception {
        switch (message.protocol()) {
            case 0 -> {
                if (handshake == Handshake.ACCEPT)
                    send(out, 0, new AcceptVersion(acceptVersion,
                            new N2NVersionData(acceptMagic, false, 0, false)).serialize());
                else if (handshake == Handshake.REFUSE)
                    send(out, 0, new Refuse(new ReasonVersionMismatch(List.of(15L))).serialize());
            }
            case 2 -> {
                if (message.tag() == 4 && !silentFirst(intersectConnections, silentIntersectConnections, message))
                    send(out, 2, intersect(message.payload()));
            }
            case 3 -> {
                if (message.tag() == 0)
                    range(message.payload(), out);
            }
            case 8 -> {
                Array items = (Array) CborDecoder.decode(message.payload()).get(0);
                if (message.tag() == 0)
                    send(out, 8, CborSerializationUtil.serialize(
                            new Array().add(new UnsignedInteger(1)).add(items.getDataItems().get(1))));
            }
            case 19 -> {
                int tag = message.tag();
                if (tag != 1 && tag != 3)
                    return true;
                if (fetch == Fetch.CLOSE)
                    return false;
                if (fetch == Fetch.SILENT)
                    return true;
                if (silentFirst(leiosFetchConnections, silentLeiosFetchConnections, message))
                    return true;
                var hold = holdLeiosFetch;
                if (hold != null)
                    hold.await();
                byte[] reply = tag == 1 ? blockReply : txsReply;
                if (tag == 3 && ebTransactions != null) {
                    List<byte[]> requested = requested(message.payload(), ebTransactions);
                    long bytes = requested.stream().mapToLong(tx -> tx.length).sum();
                    txsRequestBytes.add(bytes);
                    if (txsRequestLimit > 0 && bytes > txsRequestLimit)
                        return false; //refused: the peer drops the connection
                    if (txsRequestsAnswered > 0 && txsRequestBytes.size() > txsRequestsAnswered)
                        return true; //silent, the connection left open
                    reply = EndorserBlockResolverTest.txsMessage(requested);
                }
                if (reply != null)
                    sendLeiosFetch(out, reply, tag == 3);
                if (tag == 1 && closeAfterBlockReply)
                    return false;
            }
            default -> {
            }
        }
        return true;
    }

    /** The transactions a transactions request's bitmap selects, in index order. */
    private static List<byte[]> requested(byte[] payload, List<byte[]> transactions) {
        var request = (com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosBlockTxsRequest)
                com.bloxbean.cardano.yaci.core.protocol.leiosfetch.LeiosFetchStateBase.deserialize(payload);
        List<byte[]> selected = new ArrayList<>();
        request.getBitmap().getWindows().forEach((window, mask) -> {
            for (int bit = 0; bit < 64; bit++) {
                int index = window * 64 + bit;
                if ((mask & (1L << (63 - bit))) != 0) {
                    if (index >= transactions.size())
                        throw new IllegalStateException("Requested transaction " + index + " of "
                                + transactions.size()); //fails the test peer's connection, so the test fails fast
                    selected.add(transactions.get(index));
                }
            }
        });
        return selected;
    }

    /** True if the message's connection is one of the first {@code count} that sent this kind of message. */
    private static boolean silentFirst(java.util.Set<Integer> seen, int count, Received message) {
        synchronized (seen) {
            seen.add(message.connection());
            return seen.stream().sorted().limit(count).anyMatch(c -> c == message.connection());
        }
    }

    private byte[] intersect(byte[] payload) throws Exception {
        Array items = (Array) CborDecoder.decode(payload).get(0);
        Tip tip = tip();
        for (DataItem pointItem : ((Array) items.getDataItems().get(1)).getDataItems()) {
            List<DataItem> parts = ((Array) pointItem).getDataItems();
            long slot = ((UnsignedInteger) parts.get(0)).getValue().longValue();
            String hash = HexUtil.encodeHexString(((ByteString) parts.get(1)).getBytes());
            if (!intersectNotFound && indexOf(new Point(slot, hash)) >= 0)
                return new IntersectFound(new Point(slot, hash), tip).serialize();
        }
        return new IntersectNotFound(tip).serialize();
    }

    private void range(byte[] payload, OutputStream out) throws Exception {
        List<DataItem> items = ((Array) CborDecoder.decode(payload).get(0)).getDataItems();
        int from = indexOf(point(items.get(1)));
        int to = indexOf(point(items.get(2)));
        if (from < 0 || to < from) {
            send(out, 3, new byte[]{(byte) 0x81, 0x03}); //NoBlocks
            return;
        }
        send(out, 3, new byte[]{(byte) 0x81, 0x02}); //StartBatch
        for (int i = from; i <= to; i++)
            send(out, 3, new MsgBlock(servedInstead != null ? servedInstead : chainBytes.get(i)).serialize());
        send(out, 3, new byte[]{(byte) 0x81, 0x05}); //BatchDone
    }

    private static Point point(DataItem item) {
        List<DataItem> parts = ((Array) item).getDataItems();
        return new Point(((UnsignedInteger) parts.get(0)).getValue().longValue(),
                HexUtil.encodeHexString(((ByteString) parts.get(1)).getBytes()));
    }

    private int indexOf(Point point) {
        for (int i = 0; i < chain.size(); i++) {
            var header = chain.get(i).getHeader().getHeaderBody();
            if (header.getSlot() == point.getSlot() && header.getBlockHash().equals(point.getHash()))
                return i;
        }
        return -1;
    }

    private Tip tip() {
        if (chain.isEmpty())
            return new Tip(new Point(0, "00".repeat(32)), 0);
        var header = chain.get(chain.size() - 1).getHeader().getHeaderBody();
        return new Tip(new Point(header.getSlot(), header.getBlockHash()), header.getBlockNumber());
    }

    /** A LeiosFetch reply, whole or in delayed segments as configured. */
    private void sendLeiosFetch(OutputStream out, byte[] reply, boolean txs) throws Exception {
        int size = leiosFetchSegmentBytes;
        if (size <= 0) {
            send(out, 19, reply);
            return;
        }
        int limit = txs && leiosFetchTxsSegmentsThenSilent > 0 ? leiosFetchTxsSegmentsThenSilent : Integer.MAX_VALUE;
        for (int offset = 0, n = 0; offset < reply.length && n < limit; offset += size, n++) {
            if (n > 0)
                Thread.sleep(leiosFetchSegmentDelayMillis);
            send(out, 19, java.util.Arrays.copyOfRange(reply, offset, Math.min(reply.length, offset + size)));
        }
    }

    /** One writer per connection at a time; never a lock shared between peers or tests. */
    private static void send(OutputStream out, int protocol, byte[] payload) throws IOException {
        synchronized (out) {
            write(out, protocol, payload);
        }
    }

    private static void write(OutputStream out, int protocol, byte[] payload) throws IOException {
        int offset = 0;
        do {
            int length = Math.min(65535, payload.length - offset);
            byte[] header = new byte[8];
            header[4] = (byte) (((protocol | 0x8000) >> 8) & 0xFF);
            header[5] = (byte) (protocol & 0xFF);
            header[6] = (byte) ((length >> 8) & 0xFF);
            header[7] = (byte) (length & 0xFF);
            out.write(header);
            out.write(payload, offset, length);
            offset += length;
        } while (offset < payload.length);
        out.flush();
    }

    /** The w42 fixtures of core's tests (core/src/test/resources/leios/w42). */
    static byte[] fixture(String name) throws IOException {
        return HexUtil.decodeHexString(fixtureText(name).trim());
    }

    static String fixtureText(String name) throws IOException {
        for (String base : new String[]{"../core/src/test/resources/leios/w42", "core/src/test/resources/leios/w42"}) {
            Path path = Path.of(base, name);
            if (Files.exists(path))
                return Files.readString(path, StandardCharsets.UTF_8);
        }
        throw new IOException("Fixture not found: " + name);
    }

    static List<String> dbSyncIds() throws IOException {
        List<String> ids = new ArrayList<>();
        for (String line : fixtureText("dbsync-10027-tx-hashes.txt").lines().toList()) {
            if (!line.isBlank())
                ids.add(line.trim());
        }
        return ids;
    }
}
