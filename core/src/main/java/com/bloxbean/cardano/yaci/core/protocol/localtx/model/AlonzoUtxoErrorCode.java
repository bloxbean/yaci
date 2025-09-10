package com.bloxbean.cardano.yaci.core.protocol.localtx.model;

/**
 * Error codes for Alonzo era UTxO validation failures.
 * 
 * Based on cardano-ledger Haskell implementation:
 * Source: eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Rules/Utxo.hs (lines 625-664)
 * encFail function in EncCBOR instance for AlonzoUtxoPredFailure
 */
public enum AlonzoUtxoErrorCode {
    
    BAD_INPUTS_UTXO(0, "BadInputsUTxO", "Transaction contains invalid or non-existent inputs"),
    OUTSIDE_VALIDITY_INTERVAL_UTXO(1, "OutsideValidityIntervalUTxO", "Transaction is outside its validity interval"),
    MAX_TX_SIZE_UTXO(2, "MaxTxSizeUTxO", "Transaction size exceeds maximum allowed"),
    INPUT_SET_EMPTY_UTXO(3, "InputSetEmptyUTxO", "Transaction has no inputs"),
    FEE_TOO_SMALL_UTXO(4, "FeeTooSmallUTxO", "Transaction fee is below minimum required"),
    VALUE_NOT_CONSERVED_UTXO(5, "ValueNotConservedUTxO", "Transaction inputs and outputs don't balance"),
    OUTPUT_TOO_SMALL_UTXO(6, "OutputTooSmallUTxO", "One or more outputs are below minimum UTxO value"),
    UTXOS_FAILURE(7, "UtxosFailure", "Sub-transition failure in UTXOS rule"),
    WRONG_NETWORK(8, "WrongNetwork", "Address network ID doesn't match expected network"),
    WRONG_NETWORK_WITHDRAWAL(9, "WrongNetworkWithdrawal", "Withdrawal address network ID doesn't match"),
    OUTPUT_BOOT_ADDR_ATTRS_TOO_BIG(10, "OutputBootAddrAttrsTooBig", "Bootstrap address attributes exceed size limit"),
    // Note: Code 11 is not used in the Haskell implementation
    OUTPUT_TOO_BIG_UTXO(12, "OutputTooBigUTxO", "One or more outputs exceed maximum value size"),
    INSUFFICIENT_COLLATERAL(13, "InsufficientCollateral", "Collateral amount is insufficient for script execution"),
    SCRIPTS_NOT_PAID_UTXO(14, "ScriptsNotPaidUTxO", "Script addresses in collateral inputs are not allowed"),
    EX_UNITS_TOO_BIG_UTXO(15, "ExUnitsTooBigUTxO", "Script execution units exceed protocol limits"),
    COLLATERAL_CONTAINS_NON_ADA(16, "CollateralContainsNonADA", "Collateral inputs contain non-ADA tokens"),
    WRONG_NETWORK_IN_TX_BODY(17, "WrongNetworkInTxBody", "Wrong network ID specified in transaction body"),
    OUTSIDE_FORECAST(18, "OutsideForecast", "Slot number outside consensus forecast range"),
    TOO_MANY_COLLATERAL_INPUTS(19, "TooManyCollateralInputs", "Number of collateral inputs exceeds protocol limit"),
    NO_COLLATERAL_INPUTS(20, "NoCollateralInputs", "Transaction with scripts requires collateral inputs");
    
    private final int code;
    private final String constructorName;
    private final String description;
    
    AlonzoUtxoErrorCode(int code, String constructorName, String description) {
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
     * @return AlonzoUtxoErrorCode or null if not found
     */
    public static AlonzoUtxoErrorCode fromCode(int code) {
        for (AlonzoUtxoErrorCode errorCode : values()) {
            if (errorCode.code == code) {
                return errorCode;
            }
        }
        return null;
    }
    
    /**
     * Check if this error code is valid for Alonzo era
     * @param code The CBOR error code to check
     * @return true if valid for Alonzo era
     */
    public static boolean isValidAlonzoCode(int code) {
        return fromCode(code) != null;
    }
    
    /**
     * Check if this error code requires special collateral handling
     * @return true if this is a collateral-related error
     */
    public boolean isCollateralError() {
        return this == INSUFFICIENT_COLLATERAL || 
               this == SCRIPTS_NOT_PAID_UTXO || 
               this == COLLATERAL_CONTAINS_NON_ADA || 
               this == TOO_MANY_COLLATERAL_INPUTS || 
               this == NO_COLLATERAL_INPUTS;
    }
    
    /**
     * Check if this error code is script-related
     * @return true if this is a script execution error
     */
    public boolean isScriptError() {
        return this == SCRIPTS_NOT_PAID_UTXO || 
               this == EX_UNITS_TOO_BIG_UTXO || 
               this == NO_COLLATERAL_INPUTS ||
               this == INSUFFICIENT_COLLATERAL;
    }
}