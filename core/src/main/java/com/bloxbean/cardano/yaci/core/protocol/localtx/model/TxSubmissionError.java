package com.bloxbean.cardano.yaci.core.protocol.localtx.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.Map;

/**
 * Base class for transaction submission errors parsed from CBOR rejection messages.
 * Provides structured error information with user-friendly messages.
 */
@Getter
@AllArgsConstructor
@Builder
@ToString
public class TxSubmissionError {
    /**
     * The error code from the Cardano ledger (e.g., "FeeTooSmallUTxO", "BadInputsUTxO")
     */
    private final String errorCode;
    
    /**
     * A user-friendly error message describing the problem
     */
    private final String userMessage;
    
    /**
     * The original CBOR hex string from the node (fallback for display)
     */
    private final String originalCbor;
    
    /**
     * Additional error details as key-value pairs
     */
    private final Map<String, Object> details;
    
    /**
     * The era in which this error occurred (e.g., "Babbage", "Conway")
     */
    private final String era;
    
    /**
     * Creates an unparsed error with only the CBOR hex
     */
    public static TxSubmissionError unparsed(String cborHex) {
        return TxSubmissionError.builder()
                .errorCode("UNPARSED")
                .userMessage("Transaction rejected with error: " + cborHex)
                .originalCbor(cborHex)
                .build();
    }
    
    /**
     * Returns the display message - either the user-friendly message or the CBOR hex as fallback
     */
    public String getDisplayMessage() {
        if (userMessage != null && !userMessage.isEmpty()) {
            return userMessage;
        }
        return "Transaction rejected with error: " + originalCbor;
    }
}