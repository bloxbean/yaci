package com.bloxbean.cardano.yaci.core.protocol.leiosnotify;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosProtocolConstants;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosRawCbor;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosNotifyError;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgClientDone;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosBlockAnnouncement;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosBlockOffer;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosBlockTxsOffer;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosNotificationRequestNext;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosVotes;
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
        if (currentState != LeiosNotifyState.StIdle || writeInFlight) {
            return null;
        }
        if (shutDown) {
            return new MsgClientDone();
        }
        return new MsgLeiosNotificationRequestNext();
    }

    @Override
    public synchronized void sendRequest(Message message) {
        super.sendRequest(message);
        writeInFlight = false;
    }

    @Override
    public synchronized void receiveResponse(Message message) {
        if (message instanceof MsgLeiosNotifyError error) {
            notifyError(error.getCause());
            this.currentState = LeiosNotifyState.StIdle;
            writeNextMessageIfReady();
            return;
        }

        LeiosNotifyState state = (LeiosNotifyState) currentState;
        if (!isAllowedInbound(state, message)) {
            notifyError(new IllegalStateException("Invalid LeiosNotify inbound message " +
                    messageName(message) + " in state " + state));
            if (state == LeiosNotifyState.StBusy) {
                this.currentState = LeiosNotifyState.StIdle;
            }
            writeNextMessageIfReady();
            return;
        }

        State oldState = currentState;
        currentState = currentState.nextState(message);
        processResponse(message);
        notifyStateUpdate(oldState, currentState);
        writeNextMessageIfReady();
    }

    @Override
    protected void processResponse(Message message) {
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
        } else if (!(message instanceof MsgClientDone) && !(message instanceof MsgLeiosNotificationRequestNext)) {
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
        if (currentState != LeiosNotifyState.StIdle || writeInFlight || !isChannelActive()) {
            return;
        }
        if (!shutDown && !autoRequestNext) {
            return;
        }

        Message message = buildNextMessage();
        if (message == null) {
            return;
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
        if (state != LeiosNotifyState.StBusy) {
            return false;
        }
        return message instanceof MsgLeiosBlockAnnouncement ||
                message instanceof MsgLeiosBlockOffer ||
                message instanceof MsgLeiosBlockTxsOffer ||
                message instanceof MsgLeiosVotes;
    }

    private String messageName(Message message) {
        return message == null ? "null" : message.getClass().getSimpleName();
    }
}
