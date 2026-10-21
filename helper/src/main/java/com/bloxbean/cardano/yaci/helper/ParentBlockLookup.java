package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.network.TCPNodeClient;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgent;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainSyncAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainsyncAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Fetches a block known only by its hash: the parent of the first certifying block Yaci sees after a start,
 * whose endorser block announcement Yaci did not observe. A block header names its parent's hash but not its
 * slot, and BlockFetch needs both.
 * <p>
 * One short-lived node-to-node connection: ChainSync {@code FindIntersect} with the parent hash at each candidate
 * slot before the child (only the real point exists on chain, so the node answers with it), then one BlockFetch
 * of that point. When the caller knows the parent's point (for example the point a sync started from), no
 * search is needed.
 */
@Slf4j
class ParentBlockLookup {
    /** Slots searched before the child. Musashi produces a block every ~20 slots; 1,000 points stay under 64 KB. */
    static final int SEARCH_SLOTS = 1_000;
    private static final long TIMEOUT_SECONDS = 60;

    private final String host;
    private final int port;
    private final VersionTable versionTable;

    ParentBlockLookup(String host, int port, VersionTable versionTable) {
        this.host = host;
        this.port = port;
        this.versionTable = versionTable;
    }

    /**
     * @param parentHash hash of the wanted block
     * @param childSlot  slot of its child
     * @param knownPoint a point that may be the parent (e.g. the sync start point), or null
     */
    Block fetch(String parentHash, long childSlot, Point knownPoint) throws Exception {
        Point[] candidates;
        if (knownPoint != null && parentHash.equals(knownPoint.getHash())) {
            candidates = new Point[]{knownPoint};
        } else {
            int count = (int) Math.min(SEARCH_SLOTS, childSlot);
            candidates = new Point[count];
            for (int i = 0; i < count; i++) {
                candidates[i] = new Point(childSlot - 1 - i, parentHash);
            }
        }

        CompletableFuture<Block> result = new CompletableFuture<>();
        HandshakeAgent handshakeAgent = new HandshakeAgent(versionTable);
        ChainsyncAgent chainsyncAgent = new ChainsyncAgent(candidates);
        BlockfetchAgent blockfetchAgent = new BlockfetchAgent();
        TCPNodeClient client = new TCPNodeClient(host, port, handshakeAgent, chainsyncAgent, blockfetchAgent);

        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                chainsyncAgent.sendNextMessage();
            }
        });
        chainsyncAgent.addListener(new ChainSyncAgentListener() {
            @Override
            public void intersactFound(Tip tip, Point point) {
                blockfetchAgent.resetPoints(point, point);
                blockfetchAgent.sendNextMessage();
            }

            @Override
            public void intersactNotFound(Tip tip) {
                result.completeExceptionally(new IllegalStateException("Block " + parentHash
                        + " not found in the " + candidates.length + " slots before slot " + childSlot));
            }
        });
        blockfetchAgent.addListener(new BlockfetchAgentListener() {
            @Override
            public void blockFound(Block block) {
                result.complete(block);
            }

            @Override
            public void noBlockFound(Point from, Point to) {
                result.completeExceptionally(new IllegalStateException("Block " + from + " not served"));
            }
        });

        try {
            client.start();
            return result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            client.shutdown();
        }
    }
}
