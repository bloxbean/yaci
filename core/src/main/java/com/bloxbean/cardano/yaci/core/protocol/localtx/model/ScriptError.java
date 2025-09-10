package com.bloxbean.cardano.yaci.core.protocol.localtx.model;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.List;
import java.util.Map;

/**
 * Represents script-related transaction submission errors
 */
@Getter
@ToString(callSuper = true)
public class ScriptError extends TxSubmissionError {
    private final List<String> failingScripts;
    private final List<String> missingScripts;
    private final String scriptFailureReason;
    private final Long executionUnitsMemory;
    private final Long executionUnitsSteps;
    
    @Builder(builderMethodName = "scriptErrorBuilder")
    public ScriptError(String errorCode, String userMessage, String originalCbor,
                       Map<String, Object> details, String era,
                       List<String> failingScripts, List<String> missingScripts,
                       String scriptFailureReason, Long executionUnitsMemory, Long executionUnitsSteps) {
        super(errorCode, userMessage, originalCbor, details, era);
        this.failingScripts = failingScripts;
        this.missingScripts = missingScripts;
        this.scriptFailureReason = scriptFailureReason;
        this.executionUnitsMemory = executionUnitsMemory;
        this.executionUnitsSteps = executionUnitsSteps;
    }
}