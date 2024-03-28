package com.bloxbean.cardano.yaci.core.config;

/**
 * YaciConfig is a singleton class that holds the configuration for Yaci.
 */
public enum YaciConfig {
    INSTANCE;

    private boolean isServer;
    private boolean returnBlockCbor;
    private boolean returnTxBodyCbor;

    YaciConfig() {
        returnBlockCbor = false;
        returnTxBodyCbor = false;
    }

    /**
     * Whether Yaci mini protocol implementation is running in server mode
     * @return true is yaci is running in Server Mode
     */
    public boolean isServer() {
        return isServer;
    }

    /**
     * Sets Yaci mini protocol implementation to run in Server Mode
     */
    public void setServer(boolean server) {
        isServer = server;
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
}
