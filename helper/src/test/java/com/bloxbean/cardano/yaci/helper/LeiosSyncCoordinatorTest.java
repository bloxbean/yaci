package com.bloxbean.cardano.yaci.helper;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosPoint;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosRawCbor;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosTxBitmap;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.LeiosFetchAgent;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.helper.model.leios.EndorserBlockEvent;
import com.bloxbean.cardano.yaci.helper.model.leios.LeiosVotesEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeiosSyncCoordinatorTest {

    @Test
    void requestsTxsOnlyAfterEbIsFetchedAndTxsAreOffered() {
        LeiosFetchAgent fetchAgent = new LeiosFetchAgent();
        AtomicReference<EndorserBlockEvent> event = new AtomicReference<>();
        LeiosSyncCoordinator coordinator = new LeiosSyncCoordinator(new BlockChainDataListener() {
            @Override
            public void onEndorserBlock(EndorserBlockEvent endorserBlockEvent) {
                event.set(endorserBlockEvent);
            }
        }, fetchAgent, LeiosConfig.builder().txsOfferWaitMillis(10_000).build());

        LeiosPoint point = point(1);
        coordinator.onBlockOffer(point, 10);
        coordinator.onBlock(point, endorserBlockRaw(1));

        assertEquals(1, fetchAgent.getQueueSize());
        assertNull(event.get());

        coordinator.onBlockTxsOffer(point);

        assertEquals(2, fetchAgent.getQueueSize());
        assertNull(event.get());
        coordinator.close();
    }

    @Test
    void emitsRefsOnlyWhenTxFetchingIsDisabled() {
        AtomicReference<EndorserBlockEvent> event = new AtomicReference<>();
        LeiosSyncCoordinator coordinator = new LeiosSyncCoordinator(new BlockChainDataListener() {
            @Override
            public void onEndorserBlock(EndorserBlockEvent endorserBlockEvent) {
                event.set(endorserBlockEvent);
            }
        }, new LeiosFetchAgent(), LeiosConfig.builder().fetchTxs(false).build());

        LeiosPoint point = point(2);
        coordinator.onBlockOffer(point, 10);
        coordinator.onBlock(point, endorserBlockRaw(2));

        assertNotNull(event.get());
        assertFalse(event.get().isTxsComplete());
        assertEquals(1, event.get().getEndorserBlock().txCount());
        coordinator.close();
    }

    @Test
    void emptyEndorserBlockEmitsRefsOnlyIncompleteEvent() {
        AtomicReference<EndorserBlockEvent> event = new AtomicReference<>();
        LeiosSyncCoordinator coordinator = new LeiosSyncCoordinator(new BlockChainDataListener() {
            @Override
            public void onEndorserBlock(EndorserBlockEvent endorserBlockEvent) {
                event.set(endorserBlockEvent);
            }
        }, new LeiosFetchAgent(), LeiosConfig.defaultConfig());

        LeiosPoint point = point(20);
        coordinator.onBlockOffer(point, 10);
        coordinator.onBlock(point, emptyEndorserBlockRaw());

        assertNotNull(event.get());
        assertEquals(0, event.get().getEndorserBlock().txCount());
        assertFalse(event.get().isTxsComplete());
        coordinator.close();
    }

    @Test
    void blockFetchRequestFailureClearsLatchForRetry() {
        AtomicInteger attempts = new AtomicInteger();
        LeiosFetchAgent fetchAgent = new LeiosFetchAgent() {
            @Override
            public synchronized void requestBlock(LeiosPoint point) {
                attempts.incrementAndGet();
                throw new IllegalStateException("request failed");
            }
        };
        LeiosSyncCoordinator coordinator = new LeiosSyncCoordinator(new BlockChainDataListener() {
        }, fetchAgent, LeiosConfig.defaultConfig());

        LeiosPoint point = point(21);
        coordinator.onBlockOffer(point, 10);
        coordinator.onBlockOffer(point, 10);

        assertEquals(2, attempts.get());
        coordinator.close();
    }

    @Test
    void fetchErrorClearsBlockRequestLatchForReoffer() {
        LeiosFetchAgent fetchAgent = new LeiosFetchAgent();
        LeiosSyncCoordinator coordinator = new LeiosSyncCoordinator(new BlockChainDataListener() {
        }, fetchAgent, LeiosConfig.defaultConfig());

        LeiosPoint point = point(24);
        coordinator.onBlockOffer(point, 10);
        coordinator.onFetchError(point, new IllegalStateException("fetch failed"));
        coordinator.onBlockOffer(point, 10);

        assertEquals(2, fetchAgent.getQueueSize());
        coordinator.close();
    }

    @Test
    void txResponsesWithoutFetchedEndorserBlockAreIgnored() {
        AtomicInteger events = new AtomicInteger();
        LeiosSyncCoordinator coordinator = new LeiosSyncCoordinator(new BlockChainDataListener() {
            @Override
            public void onEndorserBlock(EndorserBlockEvent endorserBlockEvent) {
                events.incrementAndGet();
            }
        }, new LeiosFetchAgent(), LeiosConfig.defaultConfig());

        LeiosPoint point = point(22);
        coordinator.onBlockTxs(point, point, LeiosTxBitmap.firstN(1), emptyTxListRaw());

        assertEquals(0, events.get());
        coordinator.close();
    }

    @Test
    void duplicateTxResponsesEmitOnlyOnce() {
        AtomicInteger events = new AtomicInteger();
        LeiosSyncCoordinator coordinator = new LeiosSyncCoordinator(new BlockChainDataListener() {
            @Override
            public void onEndorserBlock(EndorserBlockEvent endorserBlockEvent) {
                events.incrementAndGet();
            }
        }, new LeiosFetchAgent(), LeiosConfig.builder().txsOfferWaitMillis(10_000).build());

        LeiosPoint point = point(23);
        coordinator.onBlockOffer(point, 10);
        coordinator.onBlock(point, endorserBlockRaw(23));
        coordinator.onBlockTxsOffer(point);
        coordinator.onBlockTxs(point, point, LeiosTxBitmap.firstN(1), emptyTxListRaw());
        coordinator.onBlockTxs(point, point, LeiosTxBitmap.firstN(1), emptyTxListRaw());

        assertEquals(1, events.get());
        coordinator.close();
    }

    @Test
    void attachesAnnouncementCborWhenHeaderExtensionMatchesPoint() {
        AtomicReference<EndorserBlockEvent> event = new AtomicReference<>();
        LeiosSyncCoordinator coordinator = new LeiosSyncCoordinator(new BlockChainDataListener() {
            @Override
            public void onEndorserBlock(EndorserBlockEvent endorserBlockEvent) {
                event.set(endorserBlockEvent);
            }
        }, new LeiosFetchAgent(), LeiosConfig.builder().fetchTxs(false).build());

        LeiosPoint point = point(3);
        byte[] announcement = headerAnnouncementRaw(point.getEbHash(), 10);
        coordinator.onBlockAnnouncement(LeiosRawCbor.of(announcement));
        coordinator.onBlockOffer(point, 10);
        coordinator.onBlock(point, endorserBlockRaw(3));

        assertNotNull(event.get());
        assertEquals(HexUtil.encodeHexString(announcement), event.get().getAnnouncementCbor());
        coordinator.close();
    }

    @Test
    void deliversVotesOnlyWhenConfiguredAndListenerOverridesCallback() {
        AtomicReference<LeiosVotesEvent> event = new AtomicReference<>();
        LeiosSyncCoordinator coordinator = new LeiosSyncCoordinator(new BlockChainDataListener() {
            @Override
            public void onLeiosVotes(LeiosVotesEvent votesEvent) {
                event.set(votesEvent);
            }
        }, new LeiosFetchAgent(), LeiosConfig.builder().deliverVotes(true).build());

        coordinator.onVotes(List.of(voteRaw()));

        assertNotNull(event.get());
        assertEquals(1, event.get().getVotes().size());
        coordinator.close();
    }

    @Test
    void autoModeDoesNotAttachLeiosForRangeFetchers() {
        LeiosConfig auto = LeiosConfig.defaultConfig();
        LeiosConfig enabled = LeiosConfig.builder().mode(LeiosConfig.Mode.ENABLED).build();

        assertTrue(auto.shouldAttach(Constants.MUSASHI_PROTOCOL_MAGIC));
        assertFalse(auto.shouldAttachForRange(Constants.MUSASHI_PROTOCOL_MAGIC));
        assertTrue(enabled.shouldAttachForRange(Constants.MUSASHI_PROTOCOL_MAGIC));
    }

    private LeiosRawCbor endorserBlockRaw(int seed) {
        co.nstant.in.cbor.model.Map map = new co.nstant.in.cbor.model.Map();
        map.put(new ByteString(bytes(32, seed)), new UnsignedInteger(10));
        return LeiosRawCbor.of(CborSerializationUtil.serialize(map, false));
    }

    private LeiosRawCbor emptyEndorserBlockRaw() {
        co.nstant.in.cbor.model.Map map = new co.nstant.in.cbor.model.Map();
        return LeiosRawCbor.of(CborSerializationUtil.serialize(map, false));
    }

    private LeiosRawCbor emptyTxListRaw() {
        Array txList = new Array();
        return LeiosRawCbor.of(CborSerializationUtil.serialize(txList, false));
    }

    private LeiosRawCbor voteRaw() {
        Array vote = new Array();
        vote.add(new UnsignedInteger(1));
        vote.add(new ByteString(bytes(32, 3)));
        vote.add(new UnsignedInteger(1));
        vote.add(new ByteString(bytes(48, 4)));
        return LeiosRawCbor.of(CborSerializationUtil.serialize(vote, false));
    }

    private LeiosPoint point(int seed) {
        return new LeiosPoint(100 + seed, bytes(32, seed));
    }

    private byte[] headerAnnouncementRaw(byte[] ebHash, long ebSize) {
        Array headerBody = new Array();
        headerBody.add(new UnsignedInteger(100));
        headerBody.add(new UnsignedInteger(200));
        headerBody.add(SimpleValue.NULL);
        headerBody.add(new ByteString(bytes(32, 1)));
        headerBody.add(new ByteString(bytes(32, 2)));
        headerBody.add(vrfCert(3));
        headerBody.add(new UnsignedInteger(0));
        headerBody.add(new ByteString(bytes(32, 4)));
        headerBody.add(operationalCert());
        headerBody.add(protocolVersion());

        Array announcement = new Array();
        announcement.add(new ByteString(ebHash));
        announcement.add(new UnsignedInteger(ebSize));
        headerBody.add(announcement);

        Array header = new Array();
        header.add(headerBody);
        header.add(new ByteString(bytes(64, 8)));
        return CborSerializationUtil.serialize(header, false);
    }

    private Array vrfCert(int seed) {
        Array vrfCert = new Array();
        vrfCert.add(new ByteString(bytes(32, seed)));
        vrfCert.add(new ByteString(bytes(80, seed + 1)));
        return vrfCert;
    }

    private Array operationalCert() {
        Array operationalCert = new Array();
        operationalCert.add(new ByteString(bytes(32, 5)));
        operationalCert.add(new UnsignedInteger(1));
        operationalCert.add(new UnsignedInteger(2));
        operationalCert.add(new ByteString(bytes(64, 6)));
        return operationalCert;
    }

    private Array protocolVersion() {
        Array protocolVersion = new Array();
        protocolVersion.add(new UnsignedInteger(10));
        protocolVersion.add(new UnsignedInteger(0));
        return protocolVersion;
    }

    private byte[] bytes(int length, int seed) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (seed + i);
        }
        return bytes;
    }
}
