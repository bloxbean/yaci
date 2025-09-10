package com.bloxbean.cardano.yaci.core.protocol.localtx.model;

/**
 * Error codes for Conway era UTxO validation failures.
 * 
 * Based on cardano-ledger Haskell implementation:
 * Source: eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Utxo.hs (lines 285-307)
 * EncCBOR instance for ConwayUtxoPredFailure
 * 
 * IMPORTANT: Conway era introduces a completely different error code mapping
 * compared to previous eras. The same logical error may have different codes.
 * 
 * Key differences from previous eras:
 * - UtxosFailure is now code 0 (was code 7 in Alonzo)
 * - BadInputsUTxO is now code 1 (was code 0 in previous eras) 
 * - OutputTooSmallUTxO is now code 9 (was code 6 in previous eras)
 * - Adds BabbageOutputTooSmallUTxO (code 21) and BabbageNonDisjointRefInputs (code 22)
 */
public enum ConwayUtxoErrorCode {
    
    UTXOS_FAILURE(0, "UtxosFailure", "Sub-transition failure in UTXOS rule"),
    BAD_INPUTS_UTXO(1, "BadInputsUTxO", "Transaction contains invalid or non-existent inputs"),
    OUTSIDE_VALIDITY_INTERVAL_UTXO(2, "OutsideValidityIntervalUTxO", "Transaction is outside its validity interval"),
    MAX_TX_SIZE_UTXO(3, "MaxTxSizeUTxO", "Transaction size exceeds maximum allowed"),
    INPUT_SET_EMPTY_UTXO(4, "InputSetEmptyUTxO", "Transaction has no inputs"),
    FEE_TOO_SMALL_UTXO(5, "FeeTooSmallUTxO", "Transaction fee is below minimum required"),
    VALUE_NOT_CONSERVED_UTXO(6, "ValueNotConservedUTxO", "Transaction inputs and outputs don't balance"),
    WRONG_NETWORK(7, "WrongNetwork", "Address network ID doesn't match expected network"),
    WRONG_NETWORK_WITHDRAWAL(8, "WrongNetworkWithdrawal", "Withdrawal address network ID doesn't match"),
    OUTPUT_TOO_SMALL_UTXO(9, "OutputTooSmallUTxO", "One or more outputs are below minimum UTxO value"),
    OUTPUT_BOOT_ADDR_ATTRS_TOO_BIG(10, "OutputBootAddrAttrsTooBig", "Bootstrap address attributes exceed size limit"),
    OUTPUT_TOO_BIG_UTXO(11, "OutputTooBigUTxO", "One or more outputs exceed maximum value size"),
    INSUFFICIENT_COLLATERAL(12, "InsufficientCollateral", "Collateral amount is insufficient for script execution"),
    SCRIPTS_NOT_PAID_UTXO(13, "ScriptsNotPaidUTxO", "Script addresses in collateral inputs are not allowed"),
    EX_UNITS_TOO_BIG_UTXO(14, "ExUnitsTooBigUTxO", "Script execution units exceed protocol limits"),
    COLLATERAL_CONTAINS_NON_ADA(15, "CollateralContainsNonADA", "Collateral inputs contain non-ADA tokens"),
    WRONG_NETWORK_IN_TX_BODY(16, "WrongNetworkInTxBody", "Wrong network ID specified in transaction body"),
    OUTSIDE_FORECAST(17, "OutsideForecast", "Slot number outside consensus forecast range"),
    TOO_MANY_COLLATERAL_INPUTS(18, "TooManyCollateralInputs", "Number of collateral inputs exceeds protocol limit"),
    NO_COLLATERAL_INPUTS(19, "NoCollateralInputs", "Transaction with scripts requires collateral inputs"),
    INCORRECT_TOTAL_COLLATERAL_FIELD(20, "IncorrectTotalCollateralField", "Total collateral field doesn't match actual collateral balance"),
    BABBAGE_OUTPUT_TOO_SMALL_UTXO(21, "BabbageOutputTooSmallUTxO", "Output value is below minimum UTxO requirement (Babbage calculation in Conway)"),
    BABBAGE_NON_DISJOINT_REF_INPUTS(22, "BabbageNonDisjointRefInputs", "Reference inputs overlap with transaction inputs");
    
    private final int code;
    private final String constructorName;
    private final String description;
    
    ConwayUtxoErrorCode(int code, String constructorName, String description) {
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
     * @return ConwayUtxoErrorCode or null if not found
     */
    public static ConwayUtxoErrorCode fromCode(int code) {
        for (ConwayUtxoErrorCode errorCode : values()) {
            if (errorCode.code == code) {
                return errorCode;
            }
        }
        return null;
    }
    
    /**
     * Check if this error code is valid for Conway era
     * @param code The CBOR error code to check
     * @return true if valid for Conway era
     */
    public static boolean isValidConwayCode(int code) {
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
               this == NO_COLLATERAL_INPUTS ||
               this == INCORRECT_TOTAL_COLLATERAL_FIELD;
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
    
    /**
     * Check if this is a Babbage-inherited error in Conway
     * @return true if this error comes from Babbage era
     */
    public boolean isBabbageInheritedError() {
        return this == BABBAGE_OUTPUT_TOO_SMALL_UTXO || 
               this == BABBAGE_NON_DISJOINT_REF_INPUTS;
    }
    
    /**
     * Get the equivalent error in previous eras (if exists)
     * This helps with era migration and understanding error evolution
     * @return Description of the error in previous eras
     */
    public String getPreviousEraEquivalent() {
        switch (this) {
            case BAD_INPUTS_UTXO:
                return "Code 0 in Shelley/Alonzo/Babbage";
            case OUTPUT_TOO_SMALL_UTXO:
                return "Code 6 in Shelley/Alonzo/Babbage";
            case UTXOS_FAILURE:
                return "Code 7 in Alonzo/Babbage";
            case VALUE_NOT_CONSERVED_UTXO:
                return "Code 5 in Shelley/Alonzo/Babbage";
            case FEE_TOO_SMALL_UTXO:
                return "Code 4 in Shelley/Alonzo/Babbage";
            case BABBAGE_OUTPUT_TOO_SMALL_UTXO:
                return "Code 3 in Babbage era";
            case BABBAGE_NON_DISJOINT_REF_INPUTS:
                return "Code 4 in Babbage era";
            default:
                return "Similar error exists in previous eras with different code";
        }
    }
}