package com.bloxbean.cardano.yaci.core.protocol.leiosnotify;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.Segment;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosPoint;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosProtocolConstants;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosRawCbor;
import com.bloxbean.cardano.yaci.core.protocol.leios.serializers.LeiosCborUtil;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgCanceled;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgDone;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosBlockOffer;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosBlockTxsOffer;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosNotificationRequestNext;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosNotifyError;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosVotes;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgQuit;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeiosNotifyAgentTest {

    @Test
    void startsIdleWithClientAgencyAndProtocolId18() {
        LeiosNotifyAgent agent = new LeiosNotifyAgent();

        assertEquals(LeiosNotifyState.StIdle, agent.getCurrentState());
        assertEquals(LeiosProtocolConstants.LEIOS_NOTIFY_PROTOCOL_ID, agent.getProtocolId());
        assertTrue(agent.hasAgency());
        assertInstanceOf(MsgLeiosNotificationRequestNext.class, agent.buildNextMessage());
    }

    @Test
    void requestNextMovesToBusyAndOfferMovesBackToIdle() {
        LeiosNotifyAgent agent = new LeiosNotifyAgent();
        LeiosPoint point = point(1);

        agent.sendRequest(new MsgLeiosNotificationRequestNext());
        assertEquals(LeiosNotifyState.StBusy, agent.getCurrentState());
        assertFalse(agent.hasAgency());

        agent.receiveResponse(new MsgLeiosBlockOffer(point, 44));
        assertEquals(LeiosNotifyState.StIdle, agent.getCurrentState());
        assertTrue(agent.hasAgency());
    }

    @Test
    void dispatchesOfferCallbacks() {
        LeiosNotifyAgent agent = new LeiosNotifyAgent();
        LeiosPoint point = point(2);
        AtomicReference<LeiosPoint> blockOfferPoint = new AtomicReference<>();
        AtomicReference<Long> blockOfferSize = new AtomicReference<>();
        AtomicReference<LeiosPoint> txOfferPoint = new AtomicReference<>();
        agent.addListener(new LeiosNotifyAgentListener() {
            @Override
            public void onBlockOffer(LeiosPoint point, long ebSize) {
                blockOfferPoint.set(point);
                blockOfferSize.set(ebSize);
            }

            @Override
            public void onBlockTxsOffer(LeiosPoint point) {
                txOfferPoint.set(point);
            }
        });

        agent.sendRequest(new MsgLeiosNotificationRequestNext());
        agent.receiveResponse(new MsgLeiosBlockOffer(point, 45));
        agent.sendRequest(new MsgLeiosNotificationRequestNext());
        agent.receiveResponse(new MsgLeiosBlockTxsOffer(point));

        assertEquals(point, blockOfferPoint.get());
        assertEquals(45, blockOfferSize.get());
        assertEquals(point, txOfferPoint.get());
    }

    @Test
    void dispatchesVotesCallback() {
        LeiosNotifyAgent agent = new LeiosNotifyAgent();
        LeiosRawCbor vote = LeiosCborUtil.toRawCbor(rawArray(1));
        AtomicReference<List<LeiosRawCbor>> observedVotes = new AtomicReference<>();
        agent.addListener(new LeiosNotifyAgentListener() {
            @Override
            public void onVotes(List<LeiosRawCbor> votes) {
                observedVotes.set(votes);
            }
        });

        agent.sendRequest(new MsgLeiosNotificationRequestNext());
        agent.receiveResponse(new MsgLeiosVotes(List.of(vote)));

        assertEquals(List.of(vote), observedVotes.get());
        assertEquals(LeiosNotifyState.StIdle, agent.getCurrentState());
    }

    @Test
    void shutdownSendsQuitAndServerDoneEndsProtocol() {
        LeiosNotifyAgent agent = new LeiosNotifyAgent();

        agent.shutdown();
        Message quit = agent.buildNextMessage();
        assertInstanceOf(MsgQuit.class, quit);
        agent.sendRequest(quit);

        assertEquals(LeiosNotifyState.StQuit, agent.getCurrentState());
        assertFalse(agent.isDone());
        assertFalse(agent.hasAgency());

        agent.receiveResponse(new MsgDone());

        assertTrue(agent.isDone());
        assertFalse(agent.hasAgency());
    }

    @Test
    void canceledWithoutQuitReportsErrorAndStopsRequesting() {
        LeiosNotifyAgent agent = new LeiosNotifyAgent();
        EmbeddedChannel channel = new EmbeddedChannel();
        agent.setChannel(channel);
        AtomicReference<Throwable> error = new AtomicReference<>();
        agent.addListener(new LeiosNotifyAgentListener() {
            @Override
            public void onNotifyError(Throwable observed) {
                error.set(observed);
            }
        });

        agent.start();
        channel.readOutbound();
        agent.receiveResponse(new MsgCanceled());

        assertInstanceOf(IllegalStateException.class, error.get());
        assertEquals(LeiosNotifyState.StIdle, agent.getCurrentState());
        assertFalse(agent.isAutoRequestNext());
        assertNull(channel.readOutbound(), "no RequestNext loop after an unexpected MsgCanceled");
    }

    @Test
    void shutdownWhileARequestIsOutstandingSendsQuitAtOnce() {
        //a quiet relay: nothing answers the RequestNext until it receives MsgQuit, then MsgCanceled and MsgDone
        LeiosNotifyAgent agent = new LeiosNotifyAgent();
        EmbeddedChannel channel = new EmbeddedChannel();
        agent.setChannel(channel);
        AtomicReference<Throwable> error = new AtomicReference<>();
        agent.addListener(new LeiosNotifyAgentListener() {
            @Override
            public void onNotifyError(Throwable observed) {
                error.set(observed);
            }
        });

        agent.start();
        channel.readOutbound();
        assertEquals(LeiosNotifyState.StBusy, agent.getCurrentState());
        agent.shutdown();

        Segment quit = channel.readOutbound();
        assertInstanceOf(MsgQuit.class, LeiosNotifyStateBase.deserialize(quit.getPayload()));
        assertEquals(LeiosNotifyState.StBusy, agent.getCurrentState()); //the request is still outstanding
        assertNull(channel.readOutbound(), "one MsgQuit only");

        agent.receiveResponse(new MsgCanceled());
        assertEquals(LeiosNotifyState.StQuit, agent.getCurrentState());
        assertNull(channel.readOutbound(), "nothing after MsgQuit");
        agent.receiveResponse(new MsgDone());
        assertTrue(agent.isDone());
        assertNull(error.get());
    }

    @Test
    void aNotificationAnsweringTheRequestAfterAPipelinedQuitLeadsToDone() {
        LeiosNotifyAgent agent = new LeiosNotifyAgent();
        EmbeddedChannel channel = new EmbeddedChannel();
        agent.setChannel(channel);

        agent.start();
        channel.readOutbound();
        agent.shutdown();
        channel.readOutbound(); //MsgQuit

        agent.receiveResponse(new MsgLeiosBlockTxsOffer(point(7))); //the server answered before it saw MsgQuit
        assertEquals(LeiosNotifyState.StQuit, agent.getCurrentState());
        assertNull(channel.readOutbound(), "no RequestNext after a quit");
        agent.receiveResponse(new MsgDone());
        assertTrue(agent.isDone());
    }

    @Test
    void aQuitWriteThatTimesOutIsNotSentAgain() {
        LeiosNotifyAgent agent = new LeiosNotifyAgent();
        PendingWriteHandler pendingWrites = new PendingWriteHandler();
        EmbeddedChannel channel = new EmbeddedChannel(pendingWrites);
        agent.setChannel(channel);

        agent.start();
        pendingWrites.succeed(0);                           //RequestNext out, StBusy
        agent.shutdown();
        assertEquals(2, pendingWrites.writes.size());       //MsgQuit, its write still pending

        channel.advanceTimeBy(31, java.util.concurrent.TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();                 //the write-timeout recovery runs

        assertEquals(2, pendingWrites.writes.size(), "the slow MsgQuit may still reach the peer: never a second");
        agent.receiveResponse(new MsgCanceled());
        assertEquals(LeiosNotifyState.StQuit, agent.getCurrentState());
        agent.receiveResponse(new MsgDone());
        assertTrue(agent.isDone());
    }

    @Test
    void bothRepliesBeforeThePipelinedQuitWriteCompletesEndTheProtocol() {
        LeiosNotifyAgent agent = new LeiosNotifyAgent();
        PendingWriteHandler pendingWrites = new PendingWriteHandler();
        EmbeddedChannel channel = new EmbeddedChannel(pendingWrites);
        agent.setChannel(channel);

        agent.start();
        pendingWrites.succeed(0);
        agent.shutdown();
        agent.receiveResponse(new MsgCanceled());
        agent.receiveResponse(new MsgDone());
        assertTrue(agent.isDone());

        pendingWrites.succeed(1);                           //the MsgQuit write completes late
        assertTrue(agent.isDone());
        assertEquals(2, pendingWrites.writes.size());
    }

    @Test
    void aMsgDoneBeforeTheOutstandingReplyEndsTheProtocolWithAnError() {
        LeiosNotifyAgent agent = new LeiosNotifyAgent();
        EmbeddedChannel channel = new EmbeddedChannel();
        agent.setChannel(channel);
        AtomicReference<Throwable> error = new AtomicReference<>();
        agent.addListener(new LeiosNotifyAgentListener() {
            @Override
            public void onNotifyError(Throwable observed) {
                error.set(observed);
            }
        });

        agent.start();
        channel.readOutbound();
        agent.shutdown();
        channel.readOutbound();                             //MsgQuit
        agent.receiveResponse(new MsgDone());

        assertTrue(agent.isDone());
        assertInstanceOf(IllegalStateException.class, error.get());
        assertNull(channel.readOutbound());
    }

    @Test
    void anUnreadableReplyAfterAPipelinedQuitStillLeadsToDone() {
        LeiosNotifyAgent agent = new LeiosNotifyAgent();
        EmbeddedChannel channel = new EmbeddedChannel();
        agent.setChannel(channel);

        agent.start();
        channel.readOutbound();
        agent.shutdown();
        channel.readOutbound();                             //MsgQuit
        agent.receiveResponse(LeiosNotifyStateBase.deserialize(HexUtil.decodeHexString("8109")));

        assertEquals(LeiosNotifyState.StQuit, agent.getCurrentState());
        assertNull(channel.readOutbound(), "no second MsgQuit");
        agent.receiveResponse(new MsgDone());
        assertTrue(agent.isDone());
    }

    @Test
    void aNotificationAfterShutdownIsNotPassedOn() {
        LeiosNotifyAgent agent = new LeiosNotifyAgent();
        EmbeddedChannel channel = new EmbeddedChannel();
        agent.setChannel(channel);
        AtomicReference<LeiosPoint> offered = new AtomicReference<>();
        agent.addListener(new LeiosNotifyAgentListener() {
            @Override
            public void onBlockTxsOffer(LeiosPoint point) {
                offered.set(point);
            }
        });

        agent.start();
        channel.readOutbound();
        agent.shutdown();
        channel.readOutbound();
        agent.receiveResponse(new MsgLeiosBlockTxsOffer(point(9)));

        assertNull(offered.get());
        assertEquals(LeiosNotifyState.StQuit, agent.getCurrentState());
    }

    @Test
    void parseErrorAfterQuitEndsTheProtocolWithoutASecondQuit() {
        LeiosNotifyAgent agent = new LeiosNotifyAgent();
        EmbeddedChannel channel = new EmbeddedChannel();
        agent.setChannel(channel);

        agent.shutdown();
        Segment quit = channel.readOutbound();
        assertInstanceOf(MsgQuit.class, LeiosNotifyStateBase.deserialize(quit.getPayload()));
        agent.receiveResponse(LeiosNotifyStateBase.deserialize(HexUtil.decodeHexString("8109")));

        assertTrue(agent.isDone());
        assertNull(channel.readOutbound(), "a second MsgQuit would be sent without agency");
    }

    @Test
    void invalidInboundMessageIsNotDispatchedInIdle() {
        LeiosNotifyAgent agent = new LeiosNotifyAgent();
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<LeiosPoint> blockOfferPoint = new AtomicReference<>();
        agent.addListener(new LeiosNotifyAgentListener() {
            @Override
            public void onBlockOffer(LeiosPoint point, long ebSize) {
                blockOfferPoint.set(point);
            }

            @Override
            public void onNotifyError(Throwable observed) {
                error.set(observed);
            }
        });

        agent.receiveResponse(new MsgLeiosBlockOffer(point(3), 3));

        assertEquals(LeiosNotifyState.StIdle, agent.getCurrentState());
        assertNull(blockOfferPoint.get());
        assertInstanceOf(IllegalStateException.class, error.get());
    }

    @Test
    void invalidInboundMessageInBusyReportsErrorAndReturnsIdle() {
        LeiosNotifyAgent agent = new LeiosNotifyAgent();
        AtomicReference<Throwable> error = new AtomicReference<>();
        agent.addListener(new LeiosNotifyAgentListener() {
            @Override
            public void onNotifyError(Throwable observed) {
                error.set(observed);
            }
        });

        agent.sendRequest(new MsgLeiosNotificationRequestNext());
        agent.receiveResponse(new MsgDone());

        assertEquals(LeiosNotifyState.StIdle, agent.getCurrentState());
        assertInstanceOf(IllegalStateException.class, error.get());
    }

    @Test
    void parseErrorReportsCauseAndReturnsIdle() {
        LeiosNotifyAgent agent = new LeiosNotifyAgent();
        AtomicReference<Throwable> error = new AtomicReference<>();
        agent.addListener(new LeiosNotifyAgentListener() {
            @Override
            public void onNotifyError(Throwable observed) {
                error.set(observed);
            }
        });

        agent.sendRequest(new MsgLeiosNotificationRequestNext());
        Throwable cause = new IllegalArgumentException("bad frame");
        agent.receiveResponse(new MsgLeiosNotifyError(cause));

        assertEquals(LeiosNotifyState.StIdle, agent.getCurrentState());
        assertEquals(cause, error.get());
    }

    @Test
    void parseErrorWhileStreamingReportsAndReArmsRequestNext() {
        LeiosNotifyAgent agent = new LeiosNotifyAgent();
        EmbeddedChannel channel = new EmbeddedChannel();
        agent.setChannel(channel);
        AtomicReference<Throwable> error = new AtomicReference<>();
        agent.addListener(new LeiosNotifyAgentListener() {
            @Override
            public void onNotifyError(Throwable observed) {
                error.set(observed);
            }
        });

        agent.start();
        assertInstanceOf(MsgLeiosNotificationRequestNext.class,
                LeiosNotifyStateBase.deserialize(((Segment) channel.readOutbound()).getPayload()));

        Throwable cause = new IllegalArgumentException("bad frame");
        agent.receiveResponse(new MsgLeiosNotifyError(cause));

        Segment secondRequest = channel.readOutbound();
        assertEquals(cause, error.get());
        assertEquals(LeiosNotifyState.StBusy, agent.getCurrentState());
        assertInstanceOf(MsgLeiosNotificationRequestNext.class,
                LeiosNotifyStateBase.deserialize(secondRequest.getPayload()));
    }

    @Test
    void listenerExceptionDoesNotStopStreamingLoop() {
        LeiosNotifyAgent agent = new LeiosNotifyAgent();
        EmbeddedChannel channel = new EmbeddedChannel();
        agent.setChannel(channel);
        AtomicReference<Throwable> error = new AtomicReference<>();
        agent.addListener(new LeiosNotifyAgentListener() {
            @Override
            public void onBlockTxsOffer(LeiosPoint point) {
                throw new IllegalStateException("listener failed");
            }

            @Override
            public void onNotifyError(Throwable observed) {
                error.set(observed);
            }
        });

        agent.start();
        channel.readOutbound();
        agent.receiveResponse(new MsgLeiosBlockTxsOffer(point(14)));

        Segment secondRequest = channel.readOutbound();
        assertInstanceOf(IllegalStateException.class, error.get());
        assertEquals(LeiosNotifyState.StBusy, agent.getCurrentState());
        assertInstanceOf(MsgLeiosNotificationRequestNext.class,
                LeiosNotifyStateBase.deserialize(secondRequest.getPayload()));
    }

    @Test
    void shutdownDuringTheRequestNextWriteSendsQuitOnceTheWriteCompletes() {
        LeiosNotifyAgent agent = new LeiosNotifyAgent();
        PendingWriteHandler pendingWrites = new PendingWriteHandler();
        EmbeddedChannel channel = new EmbeddedChannel(pendingWrites);
        agent.setChannel(channel);

        agent.start();
        agent.shutdown();

        assertEquals(1, pendingWrites.writes.size()); //one write at a time

        pendingWrites.succeed(0);

        assertEquals(LeiosNotifyState.StBusy, agent.getCurrentState());
        assertEquals(2, pendingWrites.writes.size()); //MsgQuit, without waiting for the reply
        pendingWrites.succeed(1);
        assertEquals(LeiosNotifyState.StBusy, agent.getCurrentState());

        agent.receiveResponse(new MsgLeiosBlockTxsOffer(point(15)));

        assertEquals(LeiosNotifyState.StQuit, agent.getCurrentState());
        assertEquals(2, pendingWrites.writes.size());
        agent.receiveResponse(new MsgDone());
        assertTrue(agent.isDone());
    }

    @Test
    void startSendsRequestNextAndAutoRequestsAfterNotification() {
        LeiosNotifyAgent agent = new LeiosNotifyAgent();
        EmbeddedChannel channel = new EmbeddedChannel();
        agent.setChannel(channel);

        agent.start();

        Segment firstRequest = channel.readOutbound();
        assertEquals(LeiosProtocolConstants.LEIOS_NOTIFY_PROTOCOL_ID, firstRequest.getProtocol());
        assertInstanceOf(MsgLeiosNotificationRequestNext.class,
                LeiosNotifyStateBase.deserialize(firstRequest.getPayload()));
        assertEquals(LeiosNotifyState.StBusy, agent.getCurrentState());

        agent.receiveResponse(new MsgLeiosBlockTxsOffer(point(4)));

        Segment secondRequest = channel.readOutbound();
        assertEquals(LeiosProtocolConstants.LEIOS_NOTIFY_PROTOCOL_ID, secondRequest.getProtocol());
        assertInstanceOf(MsgLeiosNotificationRequestNext.class,
                LeiosNotifyStateBase.deserialize(secondRequest.getPayload()));
        assertEquals(LeiosNotifyState.StBusy, agent.getCurrentState());
    }

    @Test
    void resetReturnsToIdle() {
        LeiosNotifyAgent agent = new LeiosNotifyAgent();
        agent.shutdown();
        agent.sendRequest(new MsgQuit());

        agent.reset();

        assertEquals(LeiosNotifyState.StIdle, agent.getCurrentState());
        assertInstanceOf(MsgLeiosNotificationRequestNext.class, agent.buildNextMessage());
    }

    private Array rawArray(long tag) {
        Array array = new Array();
        array.add(new UnsignedInteger(tag));
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
