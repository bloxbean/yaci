package com.bloxbean.cardano.yaci.core.model.byron;

public interface ByronHead {
    long getProtocolMagic();
    String getPrevBlock();
    String getBodyProof();
    ByronBlockCons getConsensusData();
    String getExtraData();
}
