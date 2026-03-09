package com.bloxbean.cardano.yaci.node.api;

/**
 * Enum to track the synchronization phase of the node.
 * Used to distinguish between real rollbacks (chain reorganizations) and
 * reconnection rollbacks (normal protocol behavior).
 */
public enum SyncPhase {
    /**
     * Node is still catching up to the network (initial bulk sync).
     * Rollbacks during this phase are typically due to bulk sync operations.
     */
    INITIAL_SYNC,

    /**
     * Node has just found an intersection point with a peer.
     * A rollback to the intersection point is expected and normal.
     */
    INTERSECT_PHASE,

    /**
     * Node is in steady-state operation, synchronized with the network.
     * Rollbacks during this phase indicate real chain reorganizations.
     */
    STEADY_STATE
}
