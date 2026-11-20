package com.bloxbean.cardano.yaci.core.protocol.leiosnotify;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosProtocolConstants;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosRawCbor;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosNotifyError;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgCanceled;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgDone;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosBlockAnnouncement;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosBlockOffer;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosBlockTxsOffer;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosNotificationRequestNext;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosVotes;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgQuit;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
public class LeiosNotifyAgent extends Agent<LeiosNotifyAgentListener> {
    private static final long WRITE_TIMEOUT_SECONDS = 30;

    private volatile boolean shutDown;
    private volatile boolean autoRequestNext;
    private volatile boolean writeInFlight;
    /**
     * A MsgQuit write has started: no second one is ever sent (a write that times out may still reach the peer). If it
     * went out while a request was outstanding, that request's reply leads to StQuit, then MsgDone ends the protocol.
     */
    private volatile boolean quitSent;
    private long writeGeneration;

    public LeiosNotifyAgent() {
        super(true);
        this.currentState = LeiosNotifyState.StIdle;
    }

    @Override
    public int getProtocolId() {
        return LeiosProtocolConstants.LEIOS_NOTIFY_PROTOCOL_ID;
    }

    @Override
    public boolean isDone() {
        return currentState == LeiosNotifyState.StDone;
    }

    @Override
    public Message deserializeResponse(byte[] bytes) {
        return LeiosNotifyStateBase.deserialize(bytes);
    }

    @Override
    public Message buildNextMessage() {
        if (writeInFlight) {
            return null;
        }
        //MsgQuit may follow an outstanding RequestNext (pipelined): the server then answers that request (or cancels
        //it) and sends MsgDone, so a quiet relay does not hold up the shutdown
        if (shutDown && !quitSent && (currentState == LeiosNotifyState.StIdle || canPipelineQuit())) {
            return new MsgQuit();
        }
        if (quitSent || currentState != LeiosNotifyState.StIdle) {
            return null;
        }
        return new MsgLeiosNotificationRequestNext();
    }

    private boolean canPipelineQuit() {
        return currentState == LeiosNotifyState.StBusy && !quitSent;
    }

    @Override
    public synchronized void sendRequest(Message message) {
        //a MsgQuit written by this agent has already moved the state (StIdle) or moves it with the outstanding reply
        //(StBusy); only one sent directly, without a write, moves it here
        if (!(message instanceof MsgQuit) || currentState == LeiosNotifyState.StIdle)
            super.sendRequest(message);
        writeInFlight = false;
    }

    /** After a pipelined MsgQuit, the reply to the outstanding request leads to StQuit, not StIdle. */
    private void afterBusyReply() {
        if (quitSent && currentState == LeiosNotifyState.StIdle)
            currentState = LeiosNotifyState.StQuit;
    }

    @Override
    public synchronized void receiveResponse(Message message) {
        if (message instanceof MsgLeiosNotifyError error) {
            notifyError(error.getCause());
            //An unreadable reply ends the exchange it answers: after MsgQuit the protocol is over
            if (currentState == LeiosNotifyState.StBusy) {
                this.currentState = LeiosNotifyState.StIdle;
                afterBusyReply();
            } else if (currentState == LeiosNotifyState.StQuit) {
                this.currentState = LeiosNotifyState.StDone;
            }
            writeNextMessageIfReady();
            return;
        }

        LeiosNotifyState state = (LeiosNotifyState) currentState;
        if (!isAllowedInbound(state, message)) {
            notifyError(new IllegalStateException("Invalid LeiosNotify inbound message " +
                    messageName(message) + " in state " + state));
            if (state == LeiosNotifyState.StBusy && quitSent && message instanceof MsgDone) {
                //MsgDone before the reply to the outstanding request: the peer has ended the protocol
                this.currentState = LeiosNotifyState.StDone;
            } else if (state == LeiosNotifyState.StBusy) {
                this.currentState = LeiosNotifyState.StIdle;
                afterBusyReply();
            } else if (state == LeiosNotifyState.StQuit) {
                this.currentState = LeiosNotifyState.StDone;
            }
            writeNextMessageIfReady();
            return;
        }

        State oldState = currentState;
        currentState = currentState.nextState(message);
        afterBusyReply();
        processResponse(message);
        notifyStateUpdate(oldState, currentState);
        writeNextMessageIfReady();
    }

    @Override
    protected void processResponse(Message message) {
        if (shutDown && !(message instanceof MsgCanceled))
            return; //after shutdown, notifications are not passed on (as the Haskell client drops them)
        if (message instanceof MsgLeiosBlockAnnouncement announcement) {
            dispatchListener(listener -> listener.onBlockAnnouncement(announcement.getAnnouncement()),
                    "onBlockAnnouncement", true);
        } else if (message instanceof MsgLeiosBlockOffer offer) {
            dispatchListener(listener -> listener.onBlockOffer(offer.getPoint(), offer.getEbSize()),
                    "onBlockOffer", true);
        } else if (message instanceof MsgLeiosBlockTxsOffer offer) {
            dispatchListener(listener -> listener.onBlockTxsOffer(offer.getPoint()),
                    "onBlockTxsOffer", true);
        } else if (message instanceof MsgLeiosVotes votes) {
            List<LeiosRawCbor> rawVotes = votes.getVotes();
            dispatchListener(listener -> listener.onVotes(rawVotes), "onVotes", true);
        } else if (message instanceof MsgCanceled) {
            //w42 servers cancel outstanding requests only after the client's MsgQuit. Without one, asking again
            //could loop, so stop requesting and report it.
            if (!shutDown) {
                autoRequestNext = false;
                notifyError(new IllegalStateException("LeiosNotify server canceled a request without MsgQuit; "
                        + "no further notifications are requested on this connection"));
            }
        } else if (!(message instanceof MsgDone) && !(message instanceof MsgLeiosNotificationRequestNext)) {
            log.warn("Unexpected LeiosNotify message: {}", message.getClass().getSimpleName());
        }
    }

    @Override
    public synchronized void shutdown() {
        this.shutDown = true;
        this.autoRequestNext = false;
        writeNextMessageIfReady();
    }

    @Override
    public synchronized void reset() {
        this.currentState = LeiosNotifyState.StIdle;
        this.shutDown = false;
        this.autoRequestNext = false;
        this.writeInFlight = false;
        this.quitSent = false;
        this.writeGeneration++;
    }

    public synchronized void start() {
        this.autoRequestNext = true;
        writeNextMessageIfReady();
    }

    public void stopAutoRequestNext() {
        this.autoRequestNext = false;
    }

    public boolean isAutoRequestNext() {
        return autoRequestNext;
    }

    @Override
    public synchronized void sendNextMessage() {
        writeNextMessageIfReady();
    }

    private void notifyError(Throwable error) {
        dispatchListener(listener -> listener.onNotifyError(error), "onNotifyError", false);
    }

    private synchronized void writeNextMessageIfReady() {
        if (writeInFlight && !isChannelActive()) {
            log.warn("Clearing LeiosNotify write-in-flight marker because the channel is inactive");
            writeInFlight = false;
            writeGeneration++;
        }
        if (writeInFlight || !isChannelActive()) {
            return;
        }
        if (currentState != LeiosNotifyState.StIdle && !(shutDown && canPipelineQuit())) {
            return;
        }
        if (!shutDown && !autoRequestNext) {
            return;
        }

        Message message = buildNextMessage();
        if (message == null) {
            return;
        }

        if (message instanceof MsgQuit) {
            quitSent = true;
            //awaiting MsgDone from the moment the quit is written: the reply, or a write timeout, may come before the
            //write's callback, which then changes nothing (a pipelined quit moves on with the outstanding reply)
            if (currentState == LeiosNotifyState.StIdle)
                currentState = LeiosNotifyState.StQuit;
        }
        writeInFlight = true;
        long generation = ++writeGeneration;
        writeMessage(message, () -> completeWrite(message, generation));
        scheduleWriteTimeout(generation);
    }

    private synchronized void completeWrite(Message message, long generation) {
        if (generation != writeGeneration || !writeInFlight) {
            log.debug("Ignoring stale LeiosNotify write callback for {}", messageName(message));
            return;
        }
        sendRequest(message);
        if (shutDown)
            writeNextMessageIfReady(); //a shutdown that came during this write sends its MsgQuit now
    }

    private void scheduleWriteTimeout(long generation) {
        var channel = getChannel();
        if (channel == null) {
            return;
        }
        channel.eventLoop().schedule(() -> recoverTimedOutWrite(generation),
                WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private synchronized void recoverTimedOutWrite(long generation) {
        if (generation != writeGeneration || !writeInFlight) {
            return;
        }
        log.warn("Recovering timed-out LeiosNotify write");
        writeInFlight = false;
        writeGeneration++;
        writeNextMessageIfReady();
    }

    private void notifyStateUpdate(State oldState, State newState) {
        dispatchListener(listener -> listener.onStateUpdate(oldState, newState), "onStateUpdate", true);
    }

    private void dispatchListener(Consumer<LeiosNotifyAgentListener> action,
                                  String callbackName,
                                  boolean reportCallbackError) {
        for (LeiosNotifyAgentListener listener : getAgentListeners()) {
            try {
                action.accept(listener);
            } catch (Exception e) {
                log.warn("LeiosNotify listener {} failed", callbackName, e);
                if (reportCallbackError) {
                    notifyError(e);
                }
            }
        }
    }

    private boolean isAllowedInbound(LeiosNotifyState state, Message message) {
        if (message == null) {
            return false;
        }
        if (state == LeiosNotifyState.StQuit) {
            return message instanceof MsgDone;
        }
        if (state != LeiosNotifyState.StBusy) {
            return false;
        }
        return message instanceof MsgLeiosBlockAnnouncement ||
                message instanceof MsgLeiosBlockOffer ||
                message instanceof MsgLeiosBlockTxsOffer ||
                message instanceof MsgLeiosVotes ||
                message instanceof MsgCanceled;
    }

    private String messageName(Message message) {
        return message == null ? "null" : message.getClass().getSimpleName();
    }
}
