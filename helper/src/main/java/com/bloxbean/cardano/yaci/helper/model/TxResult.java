package com.bloxbean.cardano.yaci.helper.model;

import com.bloxbean.cardano.yaci.core.protocol.localtx.model.TxSubmissionError;
import lombok.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@Builder
public class TxResult {
    private String txHash;
    private boolean accepted;
    private String errorCbor;
    
    /**
     * Human-readable error message (parsed from CBOR)
     */
    private String errorMessage;
    
    /**
     * Parsed error object with detailed information
     */
    private TxSubmissionError parsedError;
    
    /**
     * Get the error message to display. Returns the parsed message if available,
     * otherwise falls back to the raw CBOR hex.
     */
    public String getDisplayError() {
        if (errorMessage != null && !errorMessage.isEmpty()) {
            return errorMessage;
        }
        if (parsedError != null) {
            return parsedError.getDisplayMessage();
        }
        if (errorCbor != null && !errorCbor.isEmpty()) {
            return "Transaction rejected with error: " + errorCbor;
        }
        return accepted ? null : "Transaction rejected";
    }
}
