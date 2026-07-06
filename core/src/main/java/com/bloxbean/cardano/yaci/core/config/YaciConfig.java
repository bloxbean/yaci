package com.bloxbean.cardano.yaci.core.config;

/**
 * YaciConfig is a singleton class that holds the configuration for Yaci.
 */
public enum YaciConfig {
    INSTANCE;

    private boolean returnBlockCbor;
    private boolean returnTxBodyCbor;
    private boolean returnFullTxCbor;

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
