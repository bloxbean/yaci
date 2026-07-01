package com.bloxbean.cardano.yaci.core.protocol.peersharing;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.peersharing.messages.MsgDone;
import com.bloxbean.cardano.yaci.core.protocol.peersharing.messages.MsgSharePeers;
import com.bloxbean.cardano.yaci.core.protocol.peersharing.messages.MsgShareRequest;
import com.bloxbean.cardano.yaci.core.protocol.peersharing.messages.PeerAddress;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntFunction;

import static com.bloxbean.cardano.yaci.core.protocol.peersharing.PeerSharingState.StBusy;
import static com.bloxbean.cardano.yaci.core.protocol.peersharing.PeerSharingState.StDone;
import static com.bloxbean.cardano.yaci.core.protocol.peersharing.PeerSharingState.StIdle;

/**
 * Server side of the Ouroboros peer-sharing mini-protocol.
 *
 * <p>The agent is deliberately policy-free. Callers provide a bounded list of
 * peers for each request, and the agent serializes that response.</p>
 */
@Slf4j
public class PeerSharingServerAgent extends Agent<PeerSharingAgentListener> {
    private final IntFunction<List<PeerAddress>> peerProvider;
    private List<PeerAddress> pendingResponse = List.of();

    public PeerSharingServerAgent(IntFunction<List<PeerAddress>> peerProvider) {
        super(false);
        this.peerProvider = Objects.requireNonNull(peerProvider, "peerProvider");
        this.currentState = StIdle;
    }

    @Override
    public int getProtocolId() {
        return 10;
    }

    @Override
    public boolean isDone() {
        return currentState == StDone;
    }

    @Override
    protected Message buildNextMessage() {
        if (currentState == StBusy) {
            List<PeerAddress> response = pendingResponse;
            pendingResponse = List.of();
            log.debug("Sharing {} peers", response.size());
            return new MsgSharePeers(response);
        }
        return null;
    }

    @Override
    protected void processResponse(Message message) {
        if (message instanceof MsgShareRequest request) {
            int amount = Math.min(Math.max(request.getAmount(), 0), PeerSharingAgent.MAX_REQUEST_AMOUNT);
            pendingResponse = selectPeers(amount);
            return;
        }
        if (message instanceof MsgDone) {
            pendingResponse = List.of();
            return;
        }
        if (message != null) {
            log.warn("Unexpected peer-sharing message in server mode: {}", message.getClass().getSimpleName());
        }
    }

    @Override
    public void shutdown() {
        pendingResponse = List.of();
    }

    @Override
    public void reset() {
        this.currentState = StIdle;
        this.pendingResponse = List.of();
    }

    private List<PeerAddress> selectPeers(int amount) {
        if (amount <= 0) {
            return List.of();
        }
        List<PeerAddress> candidates = peerProvider.apply(amount);
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<PeerAddress> selected = new ArrayList<>(Math.min(amount, candidates.size()));
        for (PeerAddress candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            selected.add(candidate);
            if (selected.size() >= amount) {
                break;
            }
        }
        return List.copyOf(selected);
    }
}
