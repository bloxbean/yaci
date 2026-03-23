package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

/**
 * Persistence interface for epoch nonce state.
 * Allows {@link EpochNonceState} to be serialized/restored across restarts.
 */
public interface NonceStateStore {

    /**
     * Persist the serialized epoch nonce state.
     *
     * @param serialized compact binary representation from {@link EpochNonceState#serialize()}
     */
    void storeEpochNonceState(byte[] serialized);

    /**
     * Retrieve the persisted epoch nonce state.
     *
     * @return serialized bytes, or null if no state has been stored
     */
    byte[] getEpochNonceState();
}
