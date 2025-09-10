package com.bloxbean.cardano.yaci.core.protocol.localtx.model;

/**
 * Error codes for Shelley era UTxO validation failures.
 * 
 * Based on cardano-ledger Haskell implementation:
 * Source: eras/shelley/impl/src/Cardano/Ledger/Shelley/Rules/Utxo.hs (lines 238-248)
 * EncCBOR instance for ShelleyUtxoPredFailure
 */
public enum ShelleyUtxoErrorCode {
    
    BAD_INPUTS_UTXO(0, "BadInputsUTxO", "Transaction contains invalid or non-existent inputs"),
    EXPIRED_UTXO(1, "ExpiredUTxO", "Transaction has expired (TTL exceeded)"),
    MAX_TX_SIZE_UTXO(2, "MaxTxSizeUTxO", "Transaction size exceeds maximum allowed"),
    INPUT_SET_EMPTY_UTXO(3, "InputSetEmptyUTxO", "Transaction has no inputs"),
    FEE_TOO_SMALL_UTXO(4, "FeeTooSmallUTxO", "Transaction fee is below minimum required"),
    VALUE_NOT_CONSERVED_UTXO(5, "ValueNotConservedUTxO", "Transaction inputs and outputs don't balance"),
    OUTPUT_TOO_SMALL_UTXO(6, "OutputTooSmallUTxO", "One or more outputs are below minimum UTxO value"),
    UPDATE_FAILURE(7, "UpdateFailure", "Protocol parameter update proposal failed"),
    WRONG_NETWORK(8, "WrongNetwork", "Address network ID doesn't match expected network"),
    WRONG_NETWORK_WITHDRAWAL(9, "WrongNetworkWithdrawal", "Withdrawal address network ID doesn't match"),
    OUTPUT_BOOT_ADDR_ATTRS_TOO_BIG(10, "OutputBootAddrAttrsTooBig", "Bootstrap address attributes exceed size limit");
    
    private final int code;
    private final String constructorName;
    private final String description;
    
    ShelleyUtxoErrorCode(int code, String constructorName, String description) {
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
     * @return ShelleyUtxoErrorCode or null if not found
     */
    public static ShelleyUtxoErrorCode fromCode(int code) {
        for (ShelleyUtxoErrorCode errorCode : values()) {
            if (errorCode.code == code) {
                return errorCode;
            }
        }
        return null;
    }
    
    /**
     * Check if this error code is valid for Shelley era
     * @param code The CBOR error code to check
     * @return true if valid for Shelley era
     */
    public static boolean isValidShelleyCode(int code) {
        return fromCode(code) != null;
    }
}