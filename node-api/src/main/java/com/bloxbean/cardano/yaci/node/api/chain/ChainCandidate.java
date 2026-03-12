package com.bloxbean.cardano.yaci.node.api.chain;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;

/**
 * Represents a candidate chain tip presented by a peer.
 * Used by {@link ChainSelectionStrategy} to compare competing chains.
 *
 * @param peerId            unique identifier for the peer (host:port)
 * @param blockNumber       block number at the peer's tip
 * @param slot              slot number at the peer's tip
 * @param blockHash         hex-encoded hash of the peer's tip block
 * @param intersectionPoint the point where this peer's chain diverges from our current chain
 * @param forkDepth         number of blocks back from our tip to the intersection point
 */
public record ChainCandidate(
        String peerId,
        long blockNumber,
        long slot,
        String blockHash,
        Point intersectionPoint,
        long forkDepth
) {}
