package com.bloxbean.cardano.yaci.core.exception;

public class BlockParseRuntimeException extends RuntimeException {
    private Long blockNumber;
    private byte[] blockCbor;

    public BlockParseRuntimeException(Long blockNumber, byte[] blockCbor, Exception e) {
        super(e);
        this.blockNumber = blockNumber;
        this.blockCbor = blockCbor;
    }

    public BlockParseRuntimeException(Long blockNumber, byte[] blockCbor, String msg, Exception e) {
        super(msg, e);
        this.blockNumber = blockNumber;
        this.blockCbor = blockCbor;
    }

    public Long getBlockNumber() {
        return blockNumber;
    }

    public byte[] getBlockCbor() {
        return blockCbor;
    }
}
