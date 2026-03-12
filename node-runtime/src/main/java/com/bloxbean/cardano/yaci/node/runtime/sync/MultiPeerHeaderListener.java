package com.bloxbean.cardano.yaci.node.runtime.sync;

import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.byron.ByronBlockHead;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbHead;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.node.api.events.PeerDisconnectedEvent;
import com.bloxbean.cardano.yaci.node.runtime.peer.PeerConnection;
import com.bloxbean.cardano.yaci.node.runtime.peer.PeerPool;
import lombok.extern.slf4j.Slf4j;

/**
 * BlockChainDataListener for additional (non-primary) peers in multi-peer mode.
 * <p>
 * Feeds received headers into {@link HeaderFanIn} for deduplication and chain selection.
 * Block bodies are NOT processed here — only the primary peer fetches bodies
 * (BodyFetchManager handles that). Additional peers contribute headers only.
 */
@Slf4j
public class MultiPeerHeaderListener implements BlockChainDataListener {

    private final String peerId;
    private final HeaderFanIn headerFanIn;
    private final PeerPool peerPool;
    private final EventBus eventBus;

    public MultiPeerHeaderListener(String peerId, HeaderFanIn headerFanIn,
                                   PeerPool peerPool, EventBus eventBus) {
        this.peerId = peerId;
        this.headerFanIn = headerFanIn;
        this.peerPool = peerPool;
        this.eventBus = eventBus;
    }

    @Override
    public void rollforward(Tip tip, BlockHeader blockHeader, byte[] originalHeaderBytes) {
        if (blockHeader == null || blockHeader.getHeaderBody() == null) return;

        long blockNumber = blockHeader.getHeaderBody().getBlockNumber();
        long slot = blockHeader.getHeaderBody().getSlot();
        String blockHash = blockHeader.getHeaderBody().getBlockHash();

        headerFanIn.onHeaderReceived(peerId, tip, blockHash, blockNumber, slot);
    }

    @Override
    public void rollforwardByronEra(Tip tip, ByronBlockHead byronBlockHead, byte[] originalHeaderBytes) {
        if (byronBlockHead == null) return;

        long blockNumber = byronBlockHead.getConsensusData().getDifficulty().longValue();
        long slot = byronBlockHead.getConsensusData().getSlotId().getSlot();
        String blockHash = byronBlockHead.getBlockHash();

        headerFanIn.onHeaderReceived(peerId, tip, blockHash, blockNumber, slot);
    }

    @Override
    public void rollforwardByronEra(Tip tip, ByronEbHead byronEbHead, byte[] originalHeaderBytes) {
        // Byron EBBs don't advance the chain — skip for chain selection
    }

    @Override
    public void onRollback(Point point) {
        log.info("Rollback on additional peer {}: {}", peerId, point);
        // Additional peers' rollbacks don't trigger our rollback directly.
        // Chain selection handles this — if the peer's new chain is worse, we ignore it.
    }

    @Override
    public void onDisconnect() {
        log.warn("Additional peer {} disconnected", peerId);
        peerPool.getPeer(peerId).ifPresent(PeerConnection::markDisconnected);
        headerFanIn.onPeerDisconnected(peerId);

        if (eventBus != null) {
            eventBus.publish(
                    new PeerDisconnectedEvent(peerId,
                            peerPool.getPeer(peerId).map(PeerConnection::getPeerType).orElse(null),
                            PeerDisconnectedEvent.DisconnectReason.ERROR),
                    EventMetadata.builder().origin("node-runtime").build(),
                    PublishOptions.builder().build());
        }
    }

    @Override
    public void intersactFound(Tip tip, Point point) {
        log.info("Intersection found on additional peer {}: tip={}, point={}", peerId, tip, point);
        // Update peer tip in pool
        peerPool.getPeer(peerId).ifPresent(conn -> conn.updateTip(tip));
    }
}
