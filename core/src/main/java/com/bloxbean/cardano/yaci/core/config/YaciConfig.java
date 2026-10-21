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

    private boolean inlineEndorserBlockTxs = true;

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

    /**
     * Leios: true (default) to deliver a block that certifies an endorser block (EB) together with the EB's
     * transactions, so applications see every ledger transaction in {@code onBlock} without extra code.
     * Has no effect on networks without Leios.
     * @return true if endorser block transactions are inlined into the certifying block
     */
    public boolean isInlineEndorserBlockTxs() {
        return inlineEndorserBlockTxs;
    }

    /**
     * Set to false to receive certifying blocks exactly as on the wire (no endorser block transactions);
     * applications then get endorser blocks through {@code BlockChainDataListener.onEndorserBlock}.
     * @param inlineEndorserBlockTxs
     */
    public void setInlineEndorserBlockTxs(boolean inlineEndorserBlockTxs) {
        this.inlineEndorserBlockTxs = inlineEndorserBlockTxs;
    }
}
