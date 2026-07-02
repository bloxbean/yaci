package com.bloxbean.cardano.yaci.core.protocol.leiosfetch;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosPoint;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosProtocolConstants;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosTxBitmap;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgClientDone;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosBlock;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosBlockRequest;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosBlockTxs;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosBlockTxsRequest;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosFetchError;
import lombok.extern.slf4j.Slf4j;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
public class LeiosFetchAgent extends Agent<LeiosFetchAgentListener> {
    private static final long WRITE_TIMEOUT_SECONDS = 30;

    private final Queue<Message> requestQueue = new ConcurrentLinkedQueue<>();
    private volatile Message outstandingRequest;
    private volatile boolean shutDown;
    private volatile boolean writeInFlight;
    private volatile Throwable terminalFailure;
    private long writeGeneration;

    public LeiosFetchAgent() {
        super(true);
        this.currentState = LeiosFetchState.StIdle;
    }

    @Override
    public int getProtocolId() {
        return LeiosProtocolConstants.LEIOS_FETCH_PROTOCOL_ID;
    }

    @Override
    public boolean isDone() {
        return currentState == LeiosFetchState.StDone;
    }

    @Override
    public Message deserializeResponse(byte[] bytes) {
        return LeiosFetchStateBase.deserialize(bytes);
    }

    @Override
    public Message buildNextMessage() {
        if (currentState != LeiosFetchState.StIdle || writeInFlight || terminalFailure != null) {
            return null;
        }
        if (shutDown) {
            return new MsgClientDone();
        }
        return requestQueue.peek();
    }

    @Override
    public synchronized void sendRequest(Message message) {
        LeiosFetchState oldState = (LeiosFetchState) currentState;
        super.sendRequest(message);
        if (oldState == LeiosFetchState.StIdle && currentState != oldState) {
            if (message instanceof MsgLeiosBlockRequest || message instanceof MsgLeiosBlockTxsRequest) {
                requestQueue.remove(message);
                outstandingRequest = message;
            } else if (message instanceof MsgClientDone) {
                requestQueue.clear();
                outstandingRequest = null;
            }
        }
        writeInFlight = false;
    }

    @Override
    public synchronized void receiveResponse(Message message) {
        if (terminalFailure != null) {
            log.warn("Ignoring LeiosFetch response after terminal fetch failure: {}", messageName(message));
            return;
        }

        if (message instanceof MsgLeiosFetchError error) {
            failFetchStream(error.getCause());
            return;
        }

        LeiosFetchState state = (LeiosFetchState) currentState;
        if (!isAllowedInbound(state, message)) {
            failFetchStream(new IllegalStateException("Invalid LeiosFetch inbound message " +
                    messageName(message) + " in state " + state));
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
        try {
            if (message instanceof MsgLeiosBlock block) {
                handleBlock(block);
            } else if (message instanceof MsgLeiosBlockTxs blockTxs) {
                handleBlockTxs(blockTxs);
            } else {
                log.warn("Unexpected LeiosFetch message: {}", messageName(message));
            }
        } finally {
            outstandingRequest = null;
        }
    }

    @Override
    public void shutdown() {
        this.shutDown = true;
        failQueuedRequests(new IllegalStateException("LeiosFetchAgent is shutting down"));
    }

    @Override
    public synchronized void reset() {
        this.currentState = LeiosFetchState.StIdle;
        this.shutDown = false;
        this.outstandingRequest = null;
        this.writeInFlight = false;
        this.terminalFailure = null;
        this.writeGeneration++;
        this.requestQueue.clear();
    }

    public synchronized void requestBlock(LeiosPoint point) {
        ensureAvailable();
        requestQueue.add(new MsgLeiosBlockRequest(point));
        writeNextMessageIfReady();
    }

    public synchronized void requestBlockTxs(LeiosPoint point, LeiosTxBitmap bitmap) {
        ensureAvailable();
        if (bitmap.isEmpty()) {
            throw new IllegalArgumentException("Empty Leios tx bitmap requests are disabled until inbound mux " +
                    "CBOR byte-fidelity is fixed");
        }
        requestQueue.add(new MsgLeiosBlockTxsRequest(point, bitmap));
        writeNextMessageIfReady();
    }

    private void ensureAvailable() {
        if (terminalFailure != null) {
            throw new IllegalStateException("LeiosFetchAgent is failed", terminalFailure);
        }
        if (shutDown || currentState == LeiosFetchState.StDone) {
            throw new IllegalStateException("LeiosFetchAgent is shutting down");
        }
    }

    public synchronized void done() {
        shutdown();
        writeNextMessageIfReady();
    }

    @Override
    public synchronized void sendNextMessage() {
        writeNextMessageIfReady();
    }

    public int getQueueSize() {
        return requestQueue.size();
    }

    public Message getOutstandingRequest() {
        return outstandingRequest;
    }

    private void handleBlock(MsgLeiosBlock block) {
        if (!(outstandingRequest instanceof MsgLeiosBlockRequest request)) {
            notifyError(new IllegalStateException("Received block without matching block request"));
            return;
        }
        dispatchListener(listener -> listener.onBlock(request.getPoint(), block.getEndorserBlock()),
                "onBlock", request.getPoint());
    }

    private void handleBlockTxs(MsgLeiosBlockTxs blockTxs) {
        if (!(outstandingRequest instanceof MsgLeiosBlockTxsRequest request)) {
            notifyError(new IllegalStateException("Received block txs without matching tx request"));
            return;
        }
        dispatchListener(listener -> listener.onBlockTxs(
                        request.getPoint(), blockTxs.getPoint(), blockTxs.getBitmap(), blockTxs.getTxList()),
                "onBlockTxs", request.getPoint());
    }

    private boolean isAllowedInbound(LeiosFetchState state, Message message) {
        if (message == null) {
            return false;
        }
        if (state == LeiosFetchState.StBlock) {
            return message instanceof MsgLeiosBlock;
        }
        if (state == LeiosFetchState.StBlockTxs) {
            return message instanceof MsgLeiosBlockTxs;
        }
        return false;
    }

    private synchronized void writeNextMessageIfReady() {
        if (terminalFailure != null) {
            return;
        }
        if (writeInFlight && !isChannelActive()) {
            failFetchStream(new IllegalStateException("LeiosFetch write failed because the channel is inactive"));
            return;
        }
        if (currentState != LeiosFetchState.StIdle || writeInFlight || !isChannelActive()) {
            return;
        }
        if (requestQueue.isEmpty() && !shutDown) {
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
            log.debug("Ignoring stale LeiosFetch write callback for {}", messageName(message));
            return;
        }
        sendRequest(message);
    }

    private void notifyError(Throwable error) {
        dispatchError(error);
    }

    private void failFetchStream(Throwable error) {
        Throwable cause = error != null ? error : new IllegalStateException("LeiosFetch failed");
        terminalFailure = cause;
        failRequest(outstandingRequest, cause);
        failQueuedRequests(cause);
        outstandingRequest = null;
        writeInFlight = false;
        writeGeneration++;
        if (currentState != LeiosFetchState.StDone) {
            currentState = LeiosFetchState.StIdle;
        }
    }

    private void failQueuedRequests(Throwable error) {
        Message request;
        while ((request = requestQueue.poll()) != null) {
            failRequest(request, error);
        }
    }

    private void failRequest(Message request, Throwable error) {
        LeiosPoint point = requestPoint(request);
        if (point != null) {
            dispatchError(point, error);
        } else {
            dispatchError(error);
        }
    }

    private LeiosPoint requestPoint(Message request) {
        if (request instanceof MsgLeiosBlockRequest blockRequest) {
            return blockRequest.getPoint();
        }
        if (request instanceof MsgLeiosBlockTxsRequest txsRequest) {
            return txsRequest.getPoint();
        }
        return null;
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
        if (generation != writeGeneration || !writeInFlight || terminalFailure != null) {
            return;
        }
        failFetchStream(new IllegalStateException("Timed out waiting for LeiosFetch write completion"));
    }

    private void notifyStateUpdate(State oldState, State newState) {
        dispatchListener(listener -> listener.onStateUpdate(oldState, newState), "onStateUpdate", null);
    }

    private void dispatchListener(Consumer<LeiosFetchAgentListener> action,
                                  String callbackName,
                                  LeiosPoint requestedPoint) {
        for (LeiosFetchAgentListener listener : getAgentListeners()) {
            try {
                action.accept(listener);
            } catch (Exception e) {
                log.warn("LeiosFetch listener {} failed", callbackName, e);
                if (requestedPoint != null) {
                    safeDispatchError(listener, requestedPoint, e);
                } else {
                    safeDispatchError(listener, e);
                }
            }
        }
    }

    private void dispatchError(Throwable error) {
        for (LeiosFetchAgentListener listener : getAgentListeners()) {
            safeDispatchError(listener, error);
        }
    }

    private void dispatchError(LeiosPoint point, Throwable error) {
        for (LeiosFetchAgentListener listener : getAgentListeners()) {
            safeDispatchError(listener, point, error);
        }
    }

    private void safeDispatchError(LeiosFetchAgentListener listener, Throwable error) {
        try {
            listener.onFetchError(error);
        } catch (Exception e) {
            log.warn("LeiosFetch listener onFetchError failed", e);
        }
    }

    private void safeDispatchError(LeiosFetchAgentListener listener, LeiosPoint point, Throwable error) {
        try {
            listener.onFetchError(point, error);
        } catch (Exception e) {
            log.warn("LeiosFetch listener onFetchError(point) failed", e);
        }
    }

    private String messageName(Message message) {
        return message == null ? "null" : message.getClass().getSimpleName();
    }
}
