package com.bloxbean.cardano.yaci.helper;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.Tag;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.EbAnnouncement;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.network.NodeClient;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosPoint;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosRawCbor;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.LeiosFetchStateBase;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosBlock;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosBlockTxs;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR 0016 D3a and D4a against a local fake peer serving the Musashi w42 fixtures: EB {@code f951f865…} (8,534 bytes,
 * 237 transactions), announced by block 10026 at slot 204753.
 */
class EndorserBlockResolverTest {
    static final String EB = "f951f865a92d7a26817cce411b9d75b4e6e86315649602808f581e97f80675e5";

    private FakeN2NPeer peer;
    private EndorserBlockResolver resolver;
    private EbAnnouncement announcement;
    private LeiosPoint point;
    private List<byte[]> transactions;
    private Block certifying;
    private EbAnnouncementCache cache;

    @BeforeEach
    void setUp() throws Exception {
        peer = new FakeN2NPeer();
        peer.blockReply = FakeN2NPeer.fixture("msg-leios-block-f951f865.hex");
        peer.txsReply = FakeN2NPeer.fixture("msg-leios-block-txs-f951f865.hex");
        Block announcing = BlockSerializer.INSTANCE.deserialize(FakeN2NPeer.fixture("block-10026.hex"));
        announcement = announcing.getHeader().getHeaderBody().getEbAnnouncement();
        point = new LeiosPoint(announcing.getHeader().getHeaderBody().getSlot(), HexUtil.decodeHexString(EB));
        transactions = ((MsgLeiosBlockTxs) LeiosFetchStateBase.deserialize(peer.txsReply)).getTransactions();
        certifying = BlockSerializer.INSTANCE.deserialize(FakeN2NPeer.fixture("block-10027.hex"));
        resolver = resolver(EndorserBlockResolver.MAX_AGE_MILLIS, EndorserBlockResolver.MAX_IDLE_MILLIS);
    }

    @AfterEach
    void tearDown() throws Exception {
        resolver.close();
        peer.close();
    }

    @Test
    void fetchesTheEndorserBlockAndAllItsTransactions() throws Exception {
        assertThat(announcement.getHash()).isEqualTo(EB);
        assertThat(point.getSlot()).isEqualTo(204753);

        List<String> fetched = complete(announcement, deadline(5_000));

        assertThat(fetched).containsExactlyElementsOf(FakeN2NPeer.dbSyncIds());
        //one request for the EB, one for all its transactions
        assertThat(peer.received(19)).extracting(FakeN2NPeer.Received::tag).containsExactly(1, 3);
    }

    @Test
    void theSideConnectionIsReused() throws Exception {
        complete(announcement, deadline(5_000));
        complete(announcement, deadline(5_000));

        assertThat(peer.connections.get()).isEqualTo(1);
    }

    @Test
    void anEndorserBlockOfTheWrongSizeOrHashIsRejected() {
        EbAnnouncement wrongSize = new EbAnnouncement(EB, announcement.getSize() + 1);
        assertThatThrownBy(() -> complete(wrongSize, deadline(5_000)))
                .hasMessageContaining("size");

        EbAnnouncement wrongHash = new EbAnnouncement("00".repeat(32), announcement.getSize());
        assertThatThrownBy(() -> complete(wrongHash, deadline(5_000)))
                .hasMessageContaining("hash");
    }

    @Test
    void anEmptyEndorserBlockCompletesTheBlockWithNoTransactions() throws Exception {
        byte[] empty = new byte[]{(byte) 0xa0};
        peer.blockReply = new MsgLeiosBlock(LeiosRawCbor.of(empty)).serialize();

        Block complete = completeBlock(announce(empty), deadline(5_000));

        assertThat(complete.getTransactionBodies()).isEmpty();
        assertThat(complete.getLeiosEndorserBlock().getTxCount()).isZero();
        assertThat(peer.received(19)).extracting(FakeN2NPeer.Received::tag).containsExactly(1); //no tx request

        EbAnnouncement wrongHash = new EbAnnouncement("00".repeat(32), 1); //still checked
        assertThatThrownBy(() -> complete(wrongHash, deadline(5_000))).hasMessageContaining("hash");
    }

    @Test
    void aMappingFailureClosesTheSideConnection() throws Exception {
        //a transaction in the mempool form [body, witnesses, aux] that matches its entry: checked, then not mappable
        com.bloxbean.cardano.client.common.cbor.CborSpan tx = com.bloxbean.cardano.client.common.cbor.CborSpan.of(transactions.get(0));
        ByteArrayOutputStream three = new ByteArrayOutputStream();
        three.write(0x83);
        three.writeBytes(tx.get(0).bytes());
        three.writeBytes(tx.get(1).bytes());
        three.writeBytes(tx.get(2).bytes());
        byte[] mempoolForm = three.toByteArray();
        byte[] references = references(entry(Blake2bUtil.blake2bHash256(mempoolForm), mempoolForm.length));
        peer.blockReply = new MsgLeiosBlock(LeiosRawCbor.of(references)).serialize();
        peer.txsReply = txsMessage(List.of(mempoolForm));
        AtomicReference<EndorserBlockResolver.Side> side = watchSide();

        assertThatThrownBy(() -> complete(announce(references), deadline(5_000))).isInstanceOf(RuntimeException.class);

        waitUntil(() -> side.get() != null, 1_000);
        assertThat(side.get().closed).isDone();
        assertThat(resolver.currentSide()).isNull();
        waitUntil(() -> peer.openConnections.get() == 0, 5_000);
    }

    @Test
    void aReplyThatDoesNotComeWithinTheCapFailsTheAttemptAndTheNextUsesAFreshConnection() throws Exception {
        resolver.close();
        resolver = new EndorserBlockResolver("127.0.0.1", peer.port(), () -> FakeN2NPeer.MAGIC, cache, null,
                EndorserBlockResolver.MAX_AGE_MILLIS, EndorserBlockResolver.MAX_IDLE_MILLIS, 500);
        peer.silentLeiosFetchConnections = 1;

        long start = System.nanoTime();
        assertThatThrownBy(() -> complete(announcement, deadline(60_000)))
                .isInstanceOf(TimeoutException.class).hasMessageContaining("within 500 ms");
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(5_000);

        assertThat(complete(announcement, deadline(60_000))).hasSize(237);
        assertThat(peer.connections.get()).isEqualTo(2);
    }

    @Test
    void anEndorserBlockListingATransactionTwiceIsRejectedBeforeAnyTransactionIsRequested() {
        byte[] tx = transactions.get(0);
        byte[] references = references(entry(Blake2bUtil.blake2bHash256(tx), tx.length),
                entry(Blake2bUtil.blake2bHash256(tx), tx.length));
        peer.blockReply = new MsgLeiosBlock(LeiosRawCbor.of(references)).serialize();

        assertThatThrownBy(() -> complete(announce(references), deadline(5_000)))
                .hasMessageContaining("twice");
        assertThat(peer.received(19)).extracting(FakeN2NPeer.Received::tag).doesNotContain(3);
    }

    @Test
    void anEntryHashThatIsNot32BytesIsRejected() {
        byte[] tx = transactions.get(0);
        byte[] references = references(entry(Arrays.copyOf(Blake2bUtil.blake2bHash256(tx), 31), tx.length));
        peer.blockReply = new MsgLeiosBlock(LeiosRawCbor.of(references)).serialize();

        assertThatThrownBy(() -> complete(announce(references), deadline(5_000)))
                .hasMessageContaining("32 bytes");
        assertThat(peer.received(19)).extracting(FakeN2NPeer.Received::tag).doesNotContain(3);
    }

    @Test
    void aTransactionOfTheRightHashButAnotherSizeThanItsEntryIsRejected() {
        byte[] tx = transactions.get(0);
        byte[] references = references(entry(Blake2bUtil.blake2bHash256(tx), tx.length + 1));
        peer.blockReply = new MsgLeiosBlock(LeiosRawCbor.of(references)).serialize();
        peer.txsReply = txsMessage(List.of(tx));

        assertThatThrownBy(() -> complete(announce(references), deadline(5_000)))
                .hasMessageContaining("transaction 0 does not match");
    }

    @Test
    void aTransactionThatDoesNotMatchItsEntryIsRejected() throws Exception {
        List<byte[]> tampered = new ArrayList<>(transactions);
        byte[] last = tampered.get(236).clone();
        last[last.length - 2] ^= 1;
        tampered.set(236, last);
        peer.txsReply = txsMessage(tampered);

        assertThatThrownBy(() -> complete(announcement, deadline(5_000)))
                .hasMessageContaining("transaction 236");

        peer.txsReply = txsMessage(transactions.subList(0, 236));
        assertThatThrownBy(() -> complete(announcement, deadline(5_000)))
                .hasMessageContaining("Expected 237");
    }

    @Test
    void aRefusedHandshakeFailsTheAttemptAtOnce() {
        peer.handshake = FakeN2NPeer.Handshake.REFUSE;
        long start = System.nanoTime();

        assertThatThrownBy(() -> complete(announcement, deadline(5_000)))
                .hasMessageContaining("refused");
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(3_000);
    }

    @Test
    void aVersionThatWasNotOfferedOrBeforeV15OrAWrongMagicIsRejected() {
        peer.acceptVersion = 16;
        assertThatThrownBy(() -> complete(announcement, deadline(5_000)))
                .hasMessageContaining("not offered");

        peer.acceptVersion = 100; //app-layer
        assertThatThrownBy(() -> complete(announcement, deadline(5_000)))
                .hasMessageContaining("not offered");

        peer.acceptVersion = 14;
        assertThatThrownBy(() -> complete(announcement, deadline(5_000)))
                .hasMessageContaining("before V15");

        peer.acceptVersion = 15;
        peer.acceptMagic = 2;
        assertThatThrownBy(() -> complete(announcement, deadline(5_000)))
                .hasMessageContaining("magic");
    }

    @Test
    void aPeerSilentBeforeTheHandshakeEndsAtTheDeadlineAndLeavesNothingRunning() throws Exception {
        peer.handshake = FakeN2NPeer.Handshake.SILENT;
        AtomicReference<EndorserBlockResolver.Side> side = watchSide();

        assertThatThrownBy(() -> complete(announcement, deadline(500)))
                .isInstanceOf(TimeoutException.class);

        waitUntil(() -> side.get() != null, 1_000);
        waitUntil(() -> threads("yaci-leios-eb-connect").isEmpty(), 10_000); //the start thread ended
        assertThat(side.get().client.isRunning()).isFalse();
        waitUntil(() -> peer.openConnections.get() == 0, 5_000);
    }

    @Test
    void aStuckHandshakeLeavesBudgetForAnotherAttempt() throws Exception {
        peer.handshake = FakeN2NPeer.Handshake.SILENT;
        long start = System.nanoTime();
        assertThatThrownBy(() -> complete(announcement, deadline(60_000)))
                .isInstanceOf(TimeoutException.class);
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(millis).isBetween(EndorserBlockResolver.HANDSHAKE_CAP_MILLIS - 500,
                EndorserBlockResolver.HANDSHAKE_CAP_MILLIS + 5_000);
    }

    @Test
    void aPeerClosingBetweenTheEndorserBlockAndTheTransactionsFailsTheAttemptAtOnce() throws Exception {
        peer.closeAfterBlockReply = true;
        long start = System.nanoTime();

        assertThatThrownBy(() -> complete(announcement, deadline(20_000)))
                .hasMessageContaining("closed");
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(5_000);
    }

    @Test
    void aSilentPeerFailsTheAttemptAndTheNextAttemptSendsItsRequestAgain() throws Exception {
        peer.fetch = FakeN2NPeer.Fetch.SILENT;
        assertThatThrownBy(() -> complete(announcement, deadline(500)))
                .isInstanceOf(TimeoutException.class);

        peer.fetch = FakeN2NPeer.Fetch.ANSWER;
        assertThat(complete(announcement, deadline(5_000))).hasSize(237);

        List<FakeN2NPeer.Received> requests = peer.received(19).stream().filter(r -> r.tag() == 1).toList();
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).connection()).isNotEqualTo(requests.get(1).connection());
    }

    @Test
    void aPeerClosingWithoutAReplyFailsTheAttemptBeforeTheDeadline() throws Exception {
        peer.fetch = FakeN2NPeer.Fetch.CLOSE;
        long start = System.nanoTime();
        assertThatThrownBy(() -> complete(announcement, deadline(10_000)))
                .hasMessageContaining("closed");
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(5_000);

        peer.fetch = FakeN2NPeer.Fetch.ANSWER;
        assertThat(complete(announcement, deadline(5_000))).hasSize(237);
    }

    @Test
    void lateCallbacksOfAClosedConnectionAreIgnored() throws Exception {
        peer.fetch = FakeN2NPeer.Fetch.SILENT;
        AtomicReference<EndorserBlockResolver.Side> first = watchSide();
        assertThatThrownBy(() -> complete(announcement, deadline(500)))
                .isInstanceOf(TimeoutException.class);
        waitUntil(() -> first.get() != null, 1_000);

        peer.fetch = FakeN2NPeer.Fetch.ANSWER;
        assertThat(complete(announcement, deadline(5_000))).hasSize(237);
        EndorserBlockResolver.Side second = resolver.currentSide();
        assertThat(second).isNotSameAs(first.get());

        //the closed connection's agent reports a late reply and a disconnect: only its own attempt hears them
        first.get().fetchAgent.receiveResponse(LeiosFetchStateBase.deserialize(peer.blockReply));
        first.get().fetchAgent.disconnected();

        assertThat(resolver.currentSide()).isSameAs(second);
        assertThat(second.closed).isNotDone();
        assertThat(complete(announcement, deadline(5_000))).hasSize(237);
        assertThat(peer.connections.get()).isEqualTo(2);
    }

    @Test
    void theSideConnectionIsReplacedWhenOld() throws Exception {
        resolver.close();
        resolver = resolver(1_000, 600_000);
        complete(announcement, deadline(5_000));
        Thread.sleep(1_500);
        complete(announcement, deadline(5_000));
        assertThat(peer.connections.get()).isEqualTo(2);
    }

    @Test
    void theSideConnectionIsReplacedWhenIdle() throws Exception {
        resolver.close();
        resolver = resolver(600_000, 1_500);
        complete(announcement, deadline(5_000));
        complete(announcement, deadline(5_000)); //not idle yet: reused
        assertThat(peer.connections.get()).isEqualTo(1);
        Thread.sleep(2_000);
        complete(announcement, deadline(5_000));
        assertThat(peer.connections.get()).isEqualTo(2);
    }

    @Test
    void closeEndsTheIdleSideConnectionAndItsEventLoop() throws Exception {
        complete(announcement, deadline(5_000));
        EndorserBlockResolver.Side side = resolver.currentSide();

        resolver.close();

        waitUntil(() -> peer.openConnections.get() == 0, 5_000);
        assertEventLoopTerminates(side.client);
    }

    static void assertEventLoopTerminates(NodeClient client) throws Exception {
        Field field = NodeClient.class.getDeclaredField("workerGroup");
        field.setAccessible(true);
        java.util.concurrent.ExecutorService group = (java.util.concurrent.ExecutorService) field.get(client);
        if (group != null)
            assertThat(group.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
    }

    private EndorserBlockResolver resolver(long maxAgeMillis, long maxIdleMillis) {
        cache = new EbAnnouncementCache();
        return new EndorserBlockResolver("127.0.0.1", peer.port(), () -> FakeN2NPeer.MAGIC, cache,
                new ParentBlockLookup("127.0.0.1", peer.port(),
                        com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant
                                .v4AndAbove(FakeN2NPeer.MAGIC)),
                maxAgeMillis, maxIdleMillis, EndorserBlockResolver.REPLY_CAP_MILLIS);
    }

    /** Completes block 10027, whose parent announced {@code announced} at the parent's slot: the tx ids. */
    private List<String> complete(EbAnnouncement announced, AttemptDeadline deadline) throws Exception {
        return completeBlock(announced, deadline).getTransactionBodies().stream()
                .map(com.bloxbean.cardano.yaci.core.model.TransactionBody::getTxHash).toList();
    }

    private Block completeBlock(EbAnnouncement announced, AttemptDeadline deadline) throws Exception {
        cache.put(certifying.getHeader().getHeaderBody().getPrevHash(), point.getSlot(), announced);
        return resolver.complete(certifying, null, deadline);
    }

    private AtomicReference<EndorserBlockResolver.Side> watchSide() {
        AtomicReference<EndorserBlockResolver.Side> side = new AtomicReference<>();
        Thread watcher = new Thread(() -> {
            long end = System.currentTimeMillis() + 5_000;
            while (side.get() == null && System.currentTimeMillis() < end) {
                side.set(resolver.currentSide());
                Thread.onSpinWait();
            }
        });
        watcher.setDaemon(true);
        watcher.start();
        return side;
    }

    static List<Thread> threads(String name) {
        return Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().equals(name) && t.isAlive())
                .toList();
    }

    /** An EB's references: a map of tx hash to size, with the sizes as 2-byte uints. */
    private static byte[] references(byte[]... entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xa0 + entries.length);
        for (byte[] entry : entries)
            out.writeBytes(entry);
        return out.toByteArray();
    }

    private static byte[] entry(byte[] hash, int size) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x58);
        out.write(hash.length);
        out.writeBytes(hash);
        out.write(0x19);
        out.write((size >> 8) & 0xff);
        out.write(size & 0xff);
        return out.toByteArray();
    }

    private static EbAnnouncement announce(byte[] references) {
        return new EbAnnouncement(HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(references)), references.length);
    }

    static byte[] txsMessage(List<byte[]> transactions) {
        Array list = new Array();
        for (byte[] tx : transactions) {
            ByteString item = new ByteString(tx);
            item.setTag(new Tag(24));
            list.add(item);
        }
        return CborSerializationUtil.serialize(new Array().add(new UnsignedInteger(4)).add(list));
    }

    static AttemptDeadline deadline(long millis) {
        return new AttemptDeadline(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis), new CompletableFuture<>());
    }

    static void waitUntil(java.util.function.BooleanSupplier condition, long millis) throws InterruptedException {
        long end = System.currentTimeMillis() + millis;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < end)
            Thread.sleep(20);
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
