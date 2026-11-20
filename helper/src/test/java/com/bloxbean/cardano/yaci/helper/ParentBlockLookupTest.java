package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.bloxbean.cardano.yaci.helper.EndorserBlockResolverTest.deadline;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR 0016 D4b against a local fake peer serving w42 blocks 10026 (the parent, announcing EB f951f865…) and 10027.
 */
class ParentBlockLookupTest {
    private FakeN2NPeer peer;
    private ParentBlockLookup lookup;
    private HeaderBody parent;
    private HeaderBody child;

    @BeforeEach
    void setUp() throws Exception {
        peer = new FakeN2NPeer();
        peer.serve(FakeN2NPeer.fixture("block-10026.hex")).serve(FakeN2NPeer.fixture("block-10027.hex"));
        parent = BlockSerializer.INSTANCE.deserialize(FakeN2NPeer.fixture("block-10026.hex")).getHeader().getHeaderBody();
        child = BlockSerializer.INSTANCE.deserialize(FakeN2NPeer.fixture("block-10027.hex")).getHeader().getHeaderBody();
        lookup = new ParentBlockLookup("127.0.0.1", peer.port(), N2NVersionTableConstant.v4AndAbove(FakeN2NPeer.MAGIC));
    }

    @AfterEach
    void tearDown() throws Exception {
        peer.close();
    }

    @Test
    void aKnownPointIsFetchedDirectly() throws Exception {
        assertThat(child.getPrevHash()).isEqualTo(parent.getBlockHash());

        EbAnnouncementCache.Announced found = lookup.find(child.getPrevHash(), child.getSlot(),
                new Point(parent.getSlot(), parent.getBlockHash()), deadline(5_000));

        assertThat(found.slot()).isEqualTo(parent.getSlot());
        assertThat(found.announcement()).isEqualTo(parent.getEbAnnouncement());
        assertThat(peer.received(2)).isEmpty(); //no FindIntersect
        assertThat(peer.received(3)).hasSize(1);
    }

    @Test
    void otherwiseItSearchesTheSlotsBeforeTheChild() throws Exception {
        EbAnnouncementCache.Announced found = lookup.find(child.getPrevHash(), child.getSlot(), null, deadline(5_000));

        assertThat(found.slot()).isEqualTo(parent.getSlot());
        assertThat(found.announcement().getHash()).isEqualTo(EndorserBlockResolverTest.EB);
        assertThat(peer.received(2)).hasSize(1);
    }

    @Test
    void aParentFurtherBackIsFoundInALaterWindow() throws Exception {
        EbAnnouncementCache.Announced found = lookup.find(child.getPrevHash(), parent.getSlot() + 2_500, null,
                deadline(5_000));

        assertThat(found.slot()).isEqualTo(parent.getSlot());
        assertThat(peer.received(2)).hasSize(3);
    }

    @Test
    void aParentThatIsNotOnChainIsNotFoundDownToSlotZero() {
        assertThatThrownBy(() -> lookup.find("ab".repeat(32), 2_500, null, deadline(5_000)))
                .hasMessageContaining("not found");
        assertThat(peer.received(2)).hasSize(3);
    }

    @Test
    void aBlockWithAnotherHashIsRejected() throws Exception {
        peer.servedInstead = FakeN2NPeer.fixture("block-10428.hex");

        assertThatThrownBy(() -> lookup.find(child.getPrevHash(), child.getSlot(),
                new Point(parent.getSlot(), parent.getBlockHash()), deadline(5_000)))
                .hasMessageContaining("for the parent");
    }

    @Test
    void theSearchIsBoundedByTheDeadline() {
        peer.handshake = FakeN2NPeer.Handshake.SILENT;

        long start = System.nanoTime();
        assertThatThrownBy(() -> lookup.find(child.getPrevHash(), child.getSlot(), null, deadline(300)))
                .isInstanceOf(java.util.concurrent.TimeoutException.class);
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2_000);
    }
}
