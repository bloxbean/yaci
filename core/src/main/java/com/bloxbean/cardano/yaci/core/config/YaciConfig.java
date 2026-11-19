package com.bloxbean.cardano.yaci.core.config;

/**
 * YaciConfig is a singleton class that holds the configuration for Yaci.
 */
public enum YaciConfig {
    INSTANCE;

    private boolean returnBlockCbor;
    private boolean returnTxBodyCbor;
    private boolean returnFullTxCbor;

    private boolean blockFetchCheckRangeExists = false;
    private boolean serverVirtualThreads = false;

    YaciConfig() {
        returnBlockCbor = false;
        returnTxBodyCbor = false;
        returnFullTxCbor = false;
    }

    /**
     * Returns true if the block cbor is returned
     * @return
     */
    public boolean isReturnBlockCbor() {
        return returnBlockCbor;
    }

    /**
     * Set to true to return block cbor
     * @param returnBlockCbor
     */
    public void setReturnBlockCbor(boolean returnBlockCbor) {
        this.returnBlockCbor = returnBlockCbor;
    }

    /**
     * Returns true if the transaction body cbor is returned
     * @return
     */
    public boolean isReturnTxBodyCbor() {
        return returnTxBodyCbor;
    }

    /**
     *
     * @param returnTxBodyCbor
     */
    public void setReturnTxBodyCbor(boolean returnTxBodyCbor) {
        this.returnTxBodyCbor = returnTxBodyCbor;
    }

    public void setBlockFetchCheckRangeExists(boolean blockFetchCheckRangeExists) {
        this.blockFetchCheckRangeExists = blockFetchCheckRangeExists;
    }

    public boolean isBlockFetchCheckRangeExists() {
        return blockFetchCheckRangeExists;
    }

    /**
     * Returns true if per-connection server worker threads are virtual threads
     * @return
     */
    public boolean isServerVirtualThreads() {
        return serverVirtualThreads;
    }

    /**
     * Set to true to run per-connection server worker threads (the block-fetch server's batch thread) as virtual
     * threads instead of platform threads. Applies to connections opened after it is set.
     * @param serverVirtualThreads
     */
    public void setServerVirtualThreads(boolean serverVirtualThreads) {
        this.serverVirtualThreads = serverVirtualThreads;
    }

    /**
     * Returns true if full transaction cbor is returned.
     * @return
     */
    public boolean isReturnFullTxCbor() {
        return returnFullTxCbor;
    }

    /**
     * Set to true to return full transaction cbor.
     * @param returnFullTxCbor
     */
    public void setReturnFullTxCbor(boolean returnFullTxCbor) {
        this.returnFullTxCbor = returnFullTxCbor;
    }
}
