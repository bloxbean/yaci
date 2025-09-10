package com.bloxbean.cardano.yaci.core.protocol.localtx.model;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.List;
import java.util.Map;

/**
 * Represents validation-related transaction submission errors
 */
@Getter
@ToString(callSuper = true)
public class ValidationError extends TxSubmissionError {
    private final String validationRule;
    private final Long currentSlot;
    private final Long validityStart;
    private final Long validityEnd;
    private final Integer actualNetwork;
    private final Integer expectedNetwork;
    private final Long transactionSize;
    private final Long maxTransactionSize;
    private final List<String> missingSignatures;
    private final List<String> invalidSignatures;
    
    @Builder(builderMethodName = "validationErrorBuilder")
    public ValidationError(String errorCode, String userMessage, String originalCbor,
                          Map<String, Object> details, String era,
                          String validationRule, Long currentSlot,
                          Long validityStart, Long validityEnd,
                          Integer actualNetwork, Integer expectedNetwork,
                          Long transactionSize, Long maxTransactionSize,
                          List<String> missingSignatures, List<String> invalidSignatures) {
        super(errorCode, userMessage, originalCbor, details, era);
        this.validationRule = validationRule;
        this.currentSlot = currentSlot;
        this.validityStart = validityStart;
        this.validityEnd = validityEnd;
        this.actualNetwork = actualNetwork;
        this.expectedNetwork = expectedNetwork;
        this.transactionSize = transactionSize;
        this.maxTransactionSize = maxTransactionSize;
        this.missingSignatures = missingSignatures;
        this.invalidSignatures = invalidSignatures;
    }
}