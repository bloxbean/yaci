package com.bloxbean.cardano.yaci.helper.model;

import com.bloxbean.cardano.yaci.core.protocol.localtx.model.TxSubmissionError;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TxResultTest {
    
    @Test
    void testGetDisplayErrorWithErrorMessage() {
        TxResult result = TxResult.builder()
                .txHash("abc123")
                .accepted(false)
                .errorMessage("Custom error message")
                .errorCbor("820204")
                .build();
        
        String displayError = result.getDisplayError();
        
        assertThat(displayError).isEqualTo("Custom error message");
    }
    
    @Test
    void testGetDisplayErrorWithParsedError() {
        TxSubmissionError parsedError = TxSubmissionError.builder()
                .errorCode("FeeTooSmallUTxO")
                .userMessage("Fee too small error")
                .originalCbor("820204")
                .build();
        
        TxResult result = TxResult.builder()
                .txHash("abc123")
                .accepted(false)
                .parsedError(parsedError)
                .errorCbor("820204")
                .build();
        
        String displayError = result.getDisplayError();
        
        assertThat(displayError).isEqualTo("Fee too small error");
    }
    
    @Test
    void testGetDisplayErrorWithOnlyCbor() {
        TxResult result = TxResult.builder()
                .txHash("abc123")
                .accepted(false)
                .errorCbor("820204")
                .build();
        
        String displayError = result.getDisplayError();
        
        assertThat(displayError).contains("Transaction rejected with error");
        assertThat(displayError).contains("820204");
    }
    
    @Test
    void testGetDisplayErrorForAcceptedTransaction() {
        TxResult result = TxResult.builder()
                .txHash("abc123")
                .accepted(true)
                .build();
        
        String displayError = result.getDisplayError();
        
        assertThat(displayError).isNull();
    }
    
    @Test
    void testGetDisplayErrorForRejectedWithNoDetails() {
        TxResult result = TxResult.builder()
                .txHash("abc123")
                .accepted(false)
                .build();
        
        String displayError = result.getDisplayError();
        
        assertThat(displayError).isEqualTo("Transaction rejected");
    }
    
    @Test
    void testPriorityOfErrorMessages() {
        // Error message should take priority over parsed error
        TxSubmissionError parsedError = TxSubmissionError.builder()
                .errorCode("FeeTooSmallUTxO")
                .userMessage("Parsed error message")
                .originalCbor("820204")
                .build();
        
        TxResult result = TxResult.builder()
                .txHash("abc123")
                .accepted(false)
                .errorMessage("Custom error message")
                .parsedError(parsedError)
                .errorCbor("820204")
                .build();
        
        String displayError = result.getDisplayError();
        
        assertThat(displayError).isEqualTo("Custom error message");
    }
}