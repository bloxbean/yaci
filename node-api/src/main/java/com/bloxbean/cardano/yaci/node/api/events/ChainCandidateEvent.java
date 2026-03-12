package com.bloxbean.cardano.yaci.node.api.events;

import com.bloxbean.cardano.yaci.events.api.VetoableEvent;
import com.bloxbean.cardano.yaci.node.api.chain.ChainCandidate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Published when a peer presents a chain tip that differs from the current best.
 * Plugins can reject candidates (e.g., fork deeper than k, blacklisted peer).
 * <p>
 * If rejected, the candidate is discarded and chain selection skips it.
 * <p>
 * Thread safety: Same as {@link BlockConsensusEvent} — synchronous dispatch only.
 */
public final class ChainCandidateEvent implements VetoableEvent {

    private final ChainCandidate candidate;
    private final long currentBestBlockNumber;
    private final long currentBestSlot;
    private final String currentBestHash;
    private final List<Rejection> rejections = new ArrayList<>();

    public ChainCandidateEvent(ChainCandidate candidate,
                               long currentBestBlockNumber,
                               long currentBestSlot,
                               String currentBestHash) {
        this.candidate = candidate;
        this.currentBestBlockNumber = currentBestBlockNumber;
        this.currentBestSlot = currentBestSlot;
        this.currentBestHash = currentBestHash;
    }

    public ChainCandidate candidate() { return candidate; }
    public long currentBestBlockNumber() { return currentBestBlockNumber; }
    public long currentBestSlot() { return currentBestSlot; }
    public String currentBestHash() { return currentBestHash; }

    @Override
    public void reject(String source, String reason) {
        rejections.add(new Rejection(source, reason));
    }

    @Override
    public boolean isRejected() {
        return !rejections.isEmpty();
    }

    @Override
    public List<Rejection> rejections() {
        return Collections.unmodifiableList(rejections);
    }
}
