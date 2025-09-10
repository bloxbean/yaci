package com.bloxbean.cardano.yaci.core.protocol.localtx.model;

/**
 * Error codes for Babbage era UTxO validation failures.
 * 
 * Based on cardano-ledger Haskell implementation:
 * Source: eras/babbage/impl/src/Cardano/Ledger/Babbage/Rules/Utxo.hs (lines 510-513)
 * EncCBOR instance for BabbageUtxoPredFailure
 * 
 * Note: Babbage era wraps Alonzo errors in AlonzoInBabbageUtxoPredFailure (code 1),
 * so most errors are actually Alonzo errors nested within Babbage structure.
 */
public enum BabbageUtxoErrorCode {
    
    /**
     * Wraps all Alonzo era errors within Babbage context.
     * The actual error will be an AlonzoUtxoPredFailure nested inside this wrapper.
     * CBOR structure: [1, <alonzo_error>]
     */
    ALONZO_IN_BABBAGE_UTXO_PRED_FAILURE(1, "AlonzoInBabbageUtxoPredFailure", "Alonzo UTxO error within Babbage era"),
    
    /**
     * The collateral amount specified in totalCollateral field doesn't match 
     * the actual collateral balance computed from collateral inputs.
     */
    INCORRECT_TOTAL_COLLATERAL_FIELD(2, "IncorrectTotalCollateralField", "Total collateral field doesn't match actual collateral balance"),
    
    /**
     * Babbage-specific minimum UTxO calculation based on output size.
     * Different from Alonzo's OutputTooSmallUTxO due to new UTxO cost model.
     */
    BABBAGE_OUTPUT_TOO_SMALL_UTXO(3, "BabbageOutputTooSmallUTxO", "Output value is below minimum UTxO requirement (Babbage calculation)"),
    
    /**
     * Reference inputs (read-only inputs) overlap with regular transaction inputs.
     * This validation was introduced in Babbage era.
     */
    BABBAGE_NON_DISJOINT_REF_INPUTS(4, "BabbageNonDisjointRefInputs", "Reference inputs overlap with transaction inputs");
    
    private final int code;
    private final String constructorName;
    private final String description;
    
    BabbageUtxoErrorCode(int code, String constructorName, String description) {
        this.code = code;
        this.constructorName = constructorName;
        this.description = description;
    }
    
    public int getCode() {
        return code;
    }
    
    public String getConstructorName() {
        return constructorName;
    }
    
    public String getDescription() {
        return description;
    }
    
    /**
     * Get error code by numeric value
     * @param code The CBOR error code
     * @return BabbageUtxoErrorCode or null if not found
     */
    public static BabbageUtxoErrorCode fromCode(int code) {
        for (BabbageUtxoErrorCode errorCode : values()) {
            if (errorCode.code == code) {
                return errorCode;
            }
        }
        return null;
    }
    
    /**
     * Check if this error code is valid for Babbage era
     * @param code The CBOR error code to check
     * @return true if valid for Babbage era
     */
    public static boolean isValidBabbageCode(int code) {
        return fromCode(code) != null;
    }
    
    /**
     * Check if this error code wraps an Alonzo error
     * @return true if this is AlonzoInBabbageUtxoPredFailure
     */
    public boolean isAlonzoWrapper() {
        return this == ALONZO_IN_BABBAGE_UTXO_PRED_FAILURE;
    }
    
    /**
     * Check if this is a Babbage-specific error (not inherited from Alonzo)
     * @return true if this is a new Babbage-only error
     */
    public boolean isBabbageSpecific() {
        return this != ALONZO_IN_BABBAGE_UTXO_PRED_FAILURE;
    }
}