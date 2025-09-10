package com.bloxbean.cardano.yaci.core.protocol.localtx.model;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/**
 * Represents UTxO-related transaction submission errors
 */
@Getter
@ToString(callSuper = true)
public class UTxOError extends TxSubmissionError {
    private final BigInteger providedAmount;
    private final BigInteger requiredAmount;
    private final List<String> invalidInputs;
    private final List<String> invalidOutputs;
    
    @Builder(builderMethodName = "utxoErrorBuilder")
    public UTxOError(String errorCode, String userMessage, String originalCbor, 
                     Map<String, Object> details, String era,
                     BigInteger providedAmount, BigInteger requiredAmount,
                     List<String> invalidInputs, List<String> invalidOutputs) {
        super(errorCode, userMessage, originalCbor, details, era);
        this.providedAmount = providedAmount;
        this.requiredAmount = requiredAmount;
        this.invalidInputs = invalidInputs;
        this.invalidOutputs = invalidOutputs;
    }
}