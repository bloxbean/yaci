package com.bloxbean.cardano.yaci.core.protocol.leiosfetch;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.Segment;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosPoint;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosProtocolConstants;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosRawCbor;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosTxBitmap;
import com.bloxbean.cardano.yaci.core.protocol.leios.serializers.LeiosCborUtil;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgClientDone;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosBlock;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosBlockRequest;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosBlockTxs;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosBlockTxsRequest;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosFetchError;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeiosFetchAgentTest {

    @Test
    void startsIdleWithClientAgencyAndProtocolId19() {
        LeiosFetchAgent agent = new LeiosFetchAgent();

        assertEquals(LeiosFetchState.StIdle, agent.getCurrentState());
        assertEquals(LeiosProtocolConstants.LEIOS_FETCH_PROTOCOL_ID, agent.getProtocolId());
        assertTrue(agent.hasAgency());
        assertNull(agent.buildNextMessage());
    }

    @Test
    void requestBlockMovesToBusyAndDispatchesBlockCallback() {
        LeiosFetchAgent agent = new LeiosFetchAgent();
        LeiosPoint point = point(1);
        LeiosRawCbor block = LeiosCborUtil.toRawCbor(rawArray(1, 2));
        AtomicReference<LeiosPoint> requestedPoint = new AtomicReference<>();
        AtomicReference<LeiosRawCbor> observedBlock = new AtomicReference<>();
        agent.addListener(new LeiosFetchAgentListener() {
            @Override
            public void onBlock(LeiosPoint requested, LeiosRawCbor endorserBlock) {
                requestedPoint.set(requested);
                observedBlock.set(endorserBlock);
            }
        });

        agent.requestBlock(point);
        Message request = agent.buildNextMessage();
        agent.sendRequest(request);

        assertInstanceOf(MsgLeiosBlockRequest.class, request);
        assertEquals(LeiosFetchState.StBlock, agent.getCurrentState());
        assertEquals(request, agent.getOutstandingRequest());
        assertEquals(0, agent.getQueueSize());
        assertFalse(agent.hasAgency());

        agent.receiveResponse(new MsgLeiosBlock(block));

        assertEquals(LeiosFetchState.StIdle, agent.getCurrentState());
        assertEquals(point, requestedPoint.get());
        assertEquals(block, observedBlock.get());
        assertNull(agent.getOutstandingRequest());
        assertTrue(agent.hasAgency());
    }

    @Test
    void requestBlockTxsMovesToBusyAndDispatchesBlockTxsCallback() {
        LeiosFetchAgent agent = new LeiosFetchAgent();
        LeiosPoint requested = point(2);
        LeiosPoint response = point(3);
        LeiosTxBitmap bitmap = LeiosTxBitmap.fromIndices(0, 64, 65);
        LeiosRawCbor txList = LeiosCborUtil.toRawCbor(rawArray(3, 4));
        AtomicReference<LeiosPoint> requestedPoint = new AtomicReference<>();
        AtomicReference<LeiosPoint> responsePoint = new AtomicReference<>();
        AtomicReference<LeiosTxBitmap> observedBitmap = new AtomicReference<>();
        AtomicReference<LeiosRawCbor> observedTxList = new AtomicReference<>();
        agent.addListener(new LeiosFetchAgentListener() {
            @Override
            public void onBlockTxs(LeiosPoint requested, LeiosPoint response,
                                   LeiosTxBitmap responseBitmap, LeiosRawCbor txList) {
                requestedPoint.set(requested);
                responsePoint.set(response);
                observedBitmap.set(responseBitmap);
                observedTxList.set(txList);
            }
        });

        agent.requestBlockTxs(requested, bitmap);
        Message request = agent.buildNextMessage();
        agent.sendRequest(request);

        assertInstanceOf(MsgLeiosBlockTxsRequest.class, request);
        assertEquals(LeiosFetchState.StBlockTxs, agent.getCurrentState());

        agent.receiveResponse(new MsgLeiosBlockTxs(response, bitmap, txList));

        assertEquals(LeiosFetchState.StIdle, agent.getCurrentState());
        assertEquals(requested, requestedPoint.get());
        assertEquals(response, responsePoint.get());
        assertEquals(bitmap, observedBitmap.get());
        assertEquals(txList, observedTxList.get());
        assertNull(agent.getOutstandingRequest());
    }

    @Test
    void queuesSecondRequestAndSendsItAfterFirstResponse() {
        LeiosFetchAgent agent = new LeiosFetchAgent();
        EmbeddedChannel channel = new EmbeddedChannel();
        agent.setChannel(channel);

        agent.requestBlock(point(4));
        Segment first = channel.readOutbound();
        assertEquals(LeiosProtocolConstants.LEIOS_FETCH_PROTOCOL_ID, first.getProtocol());
        assertInstanceOf(MsgLeiosBlockRequest.class,
                LeiosFetchStateBase.deserialize(first.getPayload()));
        assertEquals(LeiosFetchState.StBlock, agent.getCurrentState());

        LeiosTxBitmap bitmap = LeiosTxBitmap.firstN(2);
        agent.requestBlockTxs(point(5), bitmap);
        assertNull(channel.readOutbound());
        assertEquals(1, agent.getQueueSize());

        agent.receiveResponse(new MsgLeiosBlock(LeiosCborUtil.toRawCbor(rawArray(5, 6))));

        Segment second = channel.readOutbound();
        assertEquals(LeiosProtocolConstants.LEIOS_FETCH_PROTOCOL_ID, second.getProtocol());
        Message secondMessage = LeiosFetchStateBase.deserialize(second.getPayload());
        MsgLeiosBlockTxsRequest secondRequest = assertInstanceOf(MsgLeiosBlockTxsRequest.class, secondMessage);
        assertEquals(bitmap, secondRequest.getBitmap());
        assertEquals(LeiosFetchState.StBlockTxs, agent.getCurrentState());
        assertEquals(0, agent.getQueueSize());
    }

    @Test
    void doesNotDuplicateRequestWhileAsyncWriteIsPending() {
        LeiosFetchAgent agent = new LeiosFetchAgent();
        PendingWriteHandler pendingWrites = new PendingWriteHandler();
        EmbeddedChannel channel = new EmbeddedChannel(pendingWrites);
        agent.setChannel(channel);

        agent.requestBlock(point(10));
        agent.requestBlockTxs(point(11), LeiosTxBitmap.firstN(1));

        assertEquals(1, pendingWrites.writes.size());
        assertEquals(LeiosFetchState.StIdle, agent.getCurrentState());
        assertEquals(2, agent.getQueueSize());
        assertNull(agent.getOutstandingRequest());

        pendingWrites.succeed(0);

        assertEquals(LeiosFetchState.StBlock, agent.getCurrentState());
        assertEquals(1, agent.getQueueSize());
        assertInstanceOf(MsgLeiosBlockRequest.class, agent.getOutstandingRequest());

        agent.receiveResponse(new MsgLeiosBlock(LeiosCborUtil.toRawCbor(rawArray(10, 11))));

        assertEquals(2, pendingWrites.writes.size());
        assertEquals(LeiosFetchState.StIdle, agent.getCurrentState());

        pendingWrites.succeed(1);

        assertEquals(LeiosFetchState.StBlockTxs, agent.getCurrentState());
        assertEquals(0, agent.getQueueSize());
        assertInstanceOf(MsgLeiosBlockTxsRequest.class, agent.getOutstandingRequest());
    }

    @Test
    void ignoresPendingWriteCallbackAfterErrorClearsQueue() {
        LeiosFetchAgent agent = new LeiosFetchAgent();
        PendingWriteHandler pendingWrites = new PendingWriteHandler();
        EmbeddedChannel channel = new EmbeddedChannel(pendingWrites);
        agent.setChannel(channel);
        AtomicReference<LeiosPoint> failedPoint = new AtomicReference<>();
        agent.addListener(new LeiosFetchAgentListener() {
            @Override
            public void onFetchError(LeiosPoint requestedPoint, Throwable error) {
                failedPoint.set(requestedPoint);
            }
        });

        LeiosPoint point = point(18);
        agent.requestBlock(point);
        assertEquals(1, pendingWrites.writes.size());

        Throwable cause = new IllegalArgumentException("bad frame");
        agent.receiveResponse(new MsgLeiosFetchError(cause));

        assertEquals(LeiosFetchState.StIdle, agent.getCurrentState());
        assertEquals(0, agent.getQueueSize());
        assertNull(agent.getOutstandingRequest());
        assertEquals(point, failedPoint.get());

        pendingWrites.succeed(0);

        assertEquals(LeiosFetchState.StIdle, agent.getCurrentState());
        assertEquals(0, agent.getQueueSize());
        assertNull(agent.getOutstandingRequest());

        assertThrows(IllegalStateException.class,
                () -> agent.requestBlockTxs(point(19), LeiosTxBitmap.firstN(1)));
        assertEquals(1, pendingWrites.writes.size());
    }

    @Test
    void ignoresPendingWriteCallbackAfterResetClearsQueue() {
        LeiosFetchAgent agent = new LeiosFetchAgent();
        PendingWriteHandler pendingWrites = new PendingWriteHandler();
        EmbeddedChannel channel = new EmbeddedChannel(pendingWrites);
        agent.setChannel(channel);

        agent.requestBlock(point(20));
        assertEquals(1, pendingWrites.writes.size());

        agent.reset();
        pendingWrites.succeed(0);

        assertEquals(LeiosFetchState.StIdle, agent.getCurrentState());
        assertEquals(0, agent.getQueueSize());
        assertNull(agent.getOutstandingRequest());
        assertNull(agent.buildNextMessage());
    }

    @Test
    void doneSendsClientDoneAndTransitionsTerminal() {
        LeiosFetchAgent agent = new LeiosFetchAgent();
        EmbeddedChannel channel = new EmbeddedChannel();
        agent.setChannel(channel);

        agent.done();

        Segment segment = channel.readOutbound();
        assertEquals(LeiosProtocolConstants.LEIOS_FETCH_PROTOCOL_ID, segment.getProtocol());
        assertInstanceOf(MsgClientDone.class,
                LeiosFetchStateBase.deserialize(segment.getPayload()));
        assertEquals(LeiosFetchState.StDone, agent.getCurrentState());
        assertTrue(agent.isDone());
        assertFalse(agent.hasAgency());
    }

    @Test
    void doneClearsQueuedRequestsBeforeTerminalMessage() {
        LeiosFetchAgent agent = new LeiosFetchAgent();

        agent.requestBlock(point(12));
        agent.requestBlockTxs(point(13), LeiosTxBitmap.firstN(1));
        agent.done();

        assertEquals(0, agent.getQueueSize());
        Message done = agent.buildNextMessage();
        assertInstanceOf(MsgClientDone.class, done);

        agent.sendRequest(done);

        assertEquals(LeiosFetchState.StDone, agent.getCurrentState());
        assertEquals(0, agent.getQueueSize());
    }

    @Test
    void invalidInboundMessageIsNotDispatchedInIdle() {
        LeiosFetchAgent agent = new LeiosFetchAgent();
        AtomicReference<LeiosRawCbor> observedBlock = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        agent.addListener(new LeiosFetchAgentListener() {
            @Override
            public void onBlock(LeiosPoint requestedPoint, LeiosRawCbor endorserBlock) {
                observedBlock.set(endorserBlock);
            }

            @Override
            public void onFetchError(Throwable observed) {
                error.set(observed);
            }
        });

        agent.receiveResponse(new MsgLeiosBlock(LeiosCborUtil.toRawCbor(rawArray(7, 8))));

        assertEquals(LeiosFetchState.StIdle, agent.getCurrentState());
        assertNull(observedBlock.get());
        assertInstanceOf(IllegalStateException.class, error.get());
    }

    @Test
    void invalidInboundMessageInBusyReportsErrorAndReturnsIdle() {
        LeiosFetchAgent agent = new LeiosFetchAgent();
        AtomicInteger errors = new AtomicInteger();
        agent.addListener(new LeiosFetchAgentListener() {
            @Override
            public void onFetchError(Throwable observed) {
                errors.incrementAndGet();
            }
        });

        agent.requestBlock(point(6));
        agent.sendRequest(agent.buildNextMessage());
        agent.requestBlockTxs(point(16), LeiosTxBitmap.firstN(1));
        agent.receiveResponse(new MsgLeiosBlockTxs(
                point(7), LeiosTxBitmap.firstN(1), LeiosCborUtil.toRawCbor(rawArray(8, 9))));

        assertEquals(LeiosFetchState.StIdle, agent.getCurrentState());
        assertNull(agent.getOutstandingRequest());
        assertEquals(0, agent.getQueueSize());
        assertTrue(errors.get() >= 1);
        assertThrows(IllegalStateException.class, () -> agent.requestBlock(point(21)));
    }

    @Test
    void parseErrorReportsCauseAndReturnsIdle() {
        LeiosFetchAgent agent = new LeiosFetchAgent();
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        AtomicInteger errors = new AtomicInteger();
        agent.addListener(new LeiosFetchAgentListener() {
            @Override
            public void onFetchError(Throwable observed) {
                errors.incrementAndGet();
                firstError.compareAndSet(null, observed);
            }
        });

        agent.requestBlock(point(8));
        agent.sendRequest(agent.buildNextMessage());
        agent.requestBlockTxs(point(17), LeiosTxBitmap.firstN(1));
        Throwable cause = new IllegalArgumentException("bad frame");
        agent.receiveResponse(new MsgLeiosFetchError(cause));

        assertEquals(LeiosFetchState.StIdle, agent.getCurrentState());
        assertNull(agent.getOutstandingRequest());
        assertEquals(0, agent.getQueueSize());
        assertEquals(cause, firstError.get());
        assertEquals(2, errors.get());
        assertThrows(IllegalStateException.class, () -> agent.requestBlock(point(22)));
    }

    @Test
    void lateResponseAfterFetchErrorIsNotPairedWithNextRequest() {
        LeiosFetchAgent agent = new LeiosFetchAgent();
        AtomicReference<LeiosPoint> observedPoint = new AtomicReference<>();
        agent.addListener(new LeiosFetchAgentListener() {
            @Override
            public void onBlock(LeiosPoint requestedPoint, LeiosRawCbor endorserBlock) {
                observedPoint.set(requestedPoint);
            }
        });

        agent.requestBlock(point(23));
        agent.sendRequest(agent.buildNextMessage());
        agent.receiveResponse(new MsgLeiosFetchError(new IllegalArgumentException("bad frame")));

        assertThrows(IllegalStateException.class, () -> agent.requestBlock(point(24)));

        agent.receiveResponse(new MsgLeiosBlock(LeiosCborUtil.toRawCbor(rawArray(23, 24))));

        assertNull(observedPoint.get());
        assertNull(agent.getOutstandingRequest());
    }

    @Test
    void listenerExceptionDoesNotStopQueuedFetchRequest() {
        LeiosFetchAgent agent = new LeiosFetchAgent();
        EmbeddedChannel channel = new EmbeddedChannel();
        agent.setChannel(channel);
        AtomicReference<LeiosPoint> failedPoint = new AtomicReference<>();
        agent.addListener(new LeiosFetchAgentListener() {
            @Override
            public void onBlock(LeiosPoint requestedPoint, LeiosRawCbor endorserBlock) {
                throw new IllegalStateException("listener failed");
            }

            @Override
            public void onFetchError(LeiosPoint requestedPoint, Throwable error) {
                failedPoint.set(requestedPoint);
            }
        });

        LeiosPoint first = point(25);
        LeiosPoint second = point(26);
        LeiosTxBitmap bitmap = LeiosTxBitmap.firstN(1);
        agent.requestBlock(first);
        channel.readOutbound();
        agent.requestBlockTxs(second, bitmap);

        agent.receiveResponse(new MsgLeiosBlock(LeiosCborUtil.toRawCbor(rawArray(25, 26))));

        Segment secondRequest = channel.readOutbound();
        assertEquals(first, failedPoint.get());
        assertInstanceOf(MsgLeiosBlockTxsRequest.class,
                LeiosFetchStateBase.deserialize(secondRequest.getPayload()));
        assertEquals(LeiosFetchState.StBlockTxs, agent.getCurrentState());
    }

    @Test
    void resetReturnsToIdleAndClearsQueue() {
        LeiosFetchAgent agent = new LeiosFetchAgent();

        agent.requestBlock(point(9));
        agent.shutdown();
        agent.reset();

        assertEquals(LeiosFetchState.StIdle, agent.getCurrentState());
        assertEquals(0, agent.getQueueSize());
        assertNull(agent.getOutstandingRequest());
        assertNull(agent.buildNextMessage());
    }

    private Array rawArray(long first, long second) {
        Array array = new Array();
        array.add(new UnsignedInteger(first));
        array.add(new UnsignedInteger(second));
        return array;
    }

    private LeiosPoint point(int seed) {
        byte[] hash = new byte[LeiosPoint.EB_HASH_LENGTH];
        for (int i = 0; i < hash.length; i++) {
            hash[i] = (byte) (seed + i);
        }
        return new LeiosPoint(100 + seed, hash);
    }

    private static class PendingWriteHandler extends ChannelOutboundHandlerAdapter {
        private final List<Object> writes = new ArrayList<>();
        private final List<ChannelPromise> promises = new ArrayList<>();

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            writes.add(msg);
            promises.add(promise);
        }

        private void succeed(int index) {
            promises.get(index).setSuccess();
        }
    }
}
