package com.bloxbean.cardano.yaci.core.model.byron;

public interface ByronHead<T> {
    long getProtocolMagic();
    String getPrevBlock();
    String getBodyProof();
    T getConsensusData();
    String getExtraData();
    String getBlockHash();
}
