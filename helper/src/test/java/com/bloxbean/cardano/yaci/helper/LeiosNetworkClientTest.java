package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.N2NVersionData;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosPoint;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosRawCbor;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosTxBitmap;
import com.bloxbean.cardano.yaci.helper.listener.LeiosDataListener;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeiosNetworkClientTest {

    @Test
    void activatesLeiosForCompatibleMusashiHandshake() {
        LeiosNetworkClient client = new LeiosNetworkClient("localhost", 3001);
        AtomicReference<AcceptVersion> activated = new AtomicReference<>();
        client.addDataListener(new LeiosDataListener() {
            @Override
            public void onLeiosActivated(AcceptVersion acceptVersion) {
                activated.set(acceptVersion);
            }
        });

        AcceptVersion acceptVersion = musashiV15();
        client.handleHandshakeComplete(acceptVersion);

        assertTrue(client.isLeiosActive());
        assertTrue(client.getLeiosNotifyAgent().isAutoRequestNext());
        assertEquals(acceptVersion, activated.get());
        assertEquals(acceptVersion, client.getProtocolVersion().orElseThrow());
    }

    @Test
    void doesNotActivateLeiosForIncompatibleHandshake() {
        LeiosNetworkClient client = new LeiosNetworkClient("localhost", 3001);
        AtomicReference<AcceptVersion> notActivated = new AtomicReference<>();
        client.addDataListener(new LeiosDataListener() {
            @Override
            public void onLeiosNotActivated(AcceptVersion acceptVersion) {
                notActivated.set(acceptVersion);
            }
        });

        AcceptVersion acceptVersion = new AcceptVersion(
                N2NVersionTableConstant.PROTOCOL_V14,
                new N2NVersionData(Constants.MUSASHI_PROTOCOL_MAGIC, true, 0, false));
        client.handleHandshakeComplete(acceptVersion);

        assertFalse(client.isLeiosActive());
        assertFalse(client.getLeiosNotifyAgent().isAutoRequestNext());
        assertEquals(acceptVersion, notActivated.get());
    }

    @Test
    void doesNotActivateLeiosForNonMusashiV15Handshake() {
        LeiosNetworkClient client = new LeiosNetworkClient("localhost", 3001, 42);
        AtomicReference<AcceptVersion> notActivated = new AtomicReference<>();
        client.addDataListener(new LeiosDataListener() {
            @Override
            public void onLeiosNotActivated(AcceptVersion acceptVersion) {
                notActivated.set(acceptVersion);
            }
        });

        AcceptVersion acceptVersion = new AcceptVersion(
                N2NVersionTableConstant.PROTOCOL_V15,
                new N2NVersionData(42, true, 0, false));
        client.handleHandshakeComplete(acceptVersion);

        assertFalse(client.isLeiosActive());
        assertEquals(acceptVersion, notActivated.get());
    }

    @Test
    void requestMethodsAreGatedUntilLeiosIsActive() {
        LeiosNetworkClient client = new LeiosNetworkClient("localhost", 3001);

        assertThrows(IllegalStateException.class, () -> client.requestBlock(point(1)));

        client.handleHandshakeComplete(musashiV15());
        client.requestBlockTxs(point(2), LeiosTxBitmap.firstN(1));

        assertEquals(1, client.getLeiosFetchAgent().getQueueSize());
    }

    @Test
    void rejectsEmptyTxBitmapRequestsUntilMuxByteFidelityIsFixed() {
        LeiosNetworkClient client = new LeiosNetworkClient("localhost", 3001);

        client.handleHandshakeComplete(musashiV15());

        assertThrows(IllegalArgumentException.class,
                () -> client.requestBlockTxs(point(6), LeiosTxBitmap.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> client.requestFirstBlockTxs(point(7), 0));
    }

    @Test
    void disconnectClearsActivationAndIsReportedOnce() {
        LeiosNetworkClient client = new LeiosNetworkClient("localhost", 3001);
        AtomicInteger disconnects = new AtomicInteger();
        client.addDataListener(new LeiosDataListener() {
            @Override
            public void onDisconnect() {
                disconnects.incrementAndGet();
            }
        });

        client.handleDisconnect();
        assertEquals(0, disconnects.get());

        client.handleHandshakeComplete(musashiV15());
        client.requestBlockTxs(point(4), LeiosTxBitmap.firstN(1));
        assertTrue(client.isLeiosActive());
        assertTrue(client.getLeiosNotifyAgent().isAutoRequestNext());
        assertEquals(1, client.getLeiosFetchAgent().getQueueSize());

        client.getLeiosNotifyAgent().disconnected();
        client.getLeiosFetchAgent().disconnected();

        assertFalse(client.isLeiosActive());
        assertFalse(client.getLeiosNotifyAgent().isAutoRequestNext());
        assertEquals(0, client.getLeiosFetchAgent().getQueueSize());
        assertEquals(1, disconnects.get());
        assertThrows(IllegalStateException.class, () -> client.requestBlock(point(5)));
    }

    @Test
    void forwardsNotifyAndFetchCallbacksToDataListener() {
        LeiosNetworkClient client = new LeiosNetworkClient("localhost", 3001);
        AtomicReference<LeiosPoint> blockOffer = new AtomicReference<>();
        AtomicReference<LeiosRawCbor> block = new AtomicReference<>();
        AtomicInteger fetchErrors = new AtomicInteger();
        client.addDataListener(new LeiosDataListener() {
            @Override
            public void onBlockOffer(LeiosPoint point, long ebSize) {
                blockOffer.set(point);
            }

            @Override
            public void onBlock(LeiosPoint requestedPoint, LeiosRawCbor endorserBlock) {
                block.set(endorserBlock);
            }

            @Override
            public void onFetchError(Throwable error) {
                fetchErrors.incrementAndGet();
            }
        });

        LeiosPoint point = point(3);
        LeiosRawCbor rawCbor = LeiosRawCbor.of(new byte[]{0x01});

        client.getLeiosNotifyAgent().sendRequest(client.getLeiosNotifyAgent().buildNextMessage());
        client.getLeiosNotifyAgent().receiveResponse(
                new com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosBlockOffer(point, 1));

        client.getLeiosFetchAgent().requestBlock(point);
        client.getLeiosFetchAgent().sendRequest(client.getLeiosFetchAgent().buildNextMessage());
        client.getLeiosFetchAgent().receiveResponse(
                new com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosBlock(rawCbor));
        client.getLeiosFetchAgent().receiveResponse(
                new com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosFetchError(
                        new IllegalArgumentException("bad")));

        assertEquals(point, blockOffer.get());
        assertEquals(rawCbor, block.get());
        assertEquals(1, fetchErrors.get());
    }

    @Test
    void wiresMusashiAgentsInternallyWithoutPublicRawAgentGetters() {
        LeiosNetworkClient client = new LeiosNetworkClient("localhost", 3001);

        assertEquals(18, client.getLeiosNotifyAgent().getProtocolId());
        assertEquals(19, client.getLeiosFetchAgent().getProtocolId());
        assertInstanceOf(com.bloxbean.cardano.yaci.core.protocol.leiosnotify.LeiosNotifyAgent.class,
                client.getLeiosNotifyAgent());
        assertFalse(hasPublicMethod("getLeiosNotifyAgent"));
        assertFalse(hasPublicMethod("getLeiosFetchAgent"));
    }

    private AcceptVersion musashiV15() {
        return new AcceptVersion(
                N2NVersionTableConstant.PROTOCOL_V15,
                new N2NVersionData(Constants.MUSASHI_PROTOCOL_MAGIC, true, 0, false));
    }

    private LeiosPoint point(int seed) {
        byte[] hash = new byte[LeiosPoint.EB_HASH_LENGTH];
        for (int i = 0; i < hash.length; i++) {
            hash[i] = (byte) (seed + i);
        }
        return new LeiosPoint(100 + seed, hash);
    }

    private boolean hasPublicMethod(String methodName) {
        return Arrays.stream(LeiosNetworkClient.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals(methodName)
                        && Modifier.isPublic(method.getModifiers()));
    }
}
