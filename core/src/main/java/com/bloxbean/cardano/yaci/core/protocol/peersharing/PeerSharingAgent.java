package com.bloxbean.cardano.yaci.core.protocol.peersharing;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.peersharing.messages.*;
import lombok.extern.slf4j.Slf4j;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static com.bloxbean.cardano.yaci.core.protocol.peersharing.PeerSharingState.*;

@Slf4j
public class PeerSharingAgent extends Agent<PeerSharingAgentListener> {
    public static final int DEFAULT_REQUEST_AMOUNT = 10;
    public static final int MAX_REQUEST_AMOUNT = 100;
    public static final long RESPONSE_TIMEOUT_MS = 30000; // 30 seconds

    private boolean shutDown;
    private Queue<MsgShareRequest> requestQueue;
    private int defaultRequestAmount = DEFAULT_REQUEST_AMOUNT;
    private long lastRequestTime = 0;

    public PeerSharingAgent() {
        this(true);
    }

    public PeerSharingAgent(boolean isClient) {
        super(isClient);
        this.currenState = StIdle;
        this.requestQueue = new ConcurrentLinkedQueue<>();
    }

    @Override
    public int getProtocolId() {
        return 10;
    }

    @Override
    public boolean isDone() {
        return currenState == StDone;
    }

    @Override
    protected Message buildNextMessage() {
        if (shutDown) {
            return new MsgDone();
        }

        log.debug("Current state: {}, hasAgency: {}", currenState, currenState.hasAgency(true));

        switch ((PeerSharingState) currenState) {
            case StIdle:
                if (!requestQueue.isEmpty()) {
                    MsgShareRequest request = requestQueue.poll();
                    if (log.isDebugEnabled()) {
                        log.debug("Processing next request from queue: {} (amount: {})", request, request.getAmount());
                    }
                    lastRequestTime = System.currentTimeMillis();
                    return request;
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("Sending default request with amount: {}", defaultRequestAmount);
                    }
                    lastRequestTime = System.currentTimeMillis();
                    return new MsgShareRequest(defaultRequestAmount);
                }
            case StBusy:
                // Check for timeout
                if (lastRequestTime > 0 && (System.currentTimeMillis() - lastRequestTime) > RESPONSE_TIMEOUT_MS) {
                    log.warn("Peer sharing request timed out after {} ms", RESPONSE_TIMEOUT_MS);
                    getAgentListeners().forEach(listener ->
                        listener.error("Request timed out"));
                    return new MsgDone();
                }
                return null;
            default:
                return null;
        }
    }

    @Override
    protected void processResponse(Message message) {
        if (message == null) {
            log.debug("Received null message");
            return;
        }

        if (log.isDebugEnabled()) {
            log.debug("Processing peer sharing response: {}", message.getClass().getSimpleName());
        }

        if (message instanceof MsgSharePeers) {
            MsgSharePeers sharePeers = (MsgSharePeers) message;
            int peerCount = sharePeers.getPeerAddresses() != null ? sharePeers.getPeerAddresses().size() : 0;

            if (log.isDebugEnabled()) {
                log.debug("MsgSharePeers received with {} peers", peerCount);
                if (sharePeers.getPeerAddresses() != null) {
                    sharePeers.getPeerAddresses().forEach(peer ->
                        log.debug("  Peer: {} {}:{}", peer.getType(), peer.getAddress(), peer.getPort())
                    );
                }
            }
            handleSharePeers(sharePeers);
        } else if (message instanceof MsgDone) {
            if (log.isDebugEnabled()) {
                log.debug("MsgDone received - protocol terminated by remote");
            }
            handleDone();
        } else {
            log.error("Unexpected message type received: {}", message.getClass().getSimpleName());
        }
    }

    private void handleSharePeers(MsgSharePeers sharePeers) {
        getAgentListeners().forEach(listener ->
            listener.peersReceived(sharePeers.getPeerAddresses()));
    }

    private void handleDone() {
        getAgentListeners().forEach(PeerSharingAgentListener::protocolCompleted);
    }

    @Override
    public void shutdown() {
        this.shutDown = true;
    }

    @Override
    public void reset() {
        this.currenState = StIdle;
        this.shutDown = false;
        requestQueue.clear();
    }

    public void requestPeers(int amount) {
        if (amount <= 0 || amount > MAX_REQUEST_AMOUNT) {
            throw new IllegalArgumentException("Amount must be between 1 and " + MAX_REQUEST_AMOUNT);
        }

        requestQueue.add(new MsgShareRequest(amount));
        sendNextMessage();
    }

    public void setDefaultRequestAmount(int defaultRequestAmount) {
        if (defaultRequestAmount <= 0 || defaultRequestAmount > MAX_REQUEST_AMOUNT) {
            throw new IllegalArgumentException("Default amount must be between 1 and " + MAX_REQUEST_AMOUNT);
        }
        this.defaultRequestAmount = defaultRequestAmount;
    }

    public int getDefaultRequestAmount() {
        return defaultRequestAmount;
    }
}
