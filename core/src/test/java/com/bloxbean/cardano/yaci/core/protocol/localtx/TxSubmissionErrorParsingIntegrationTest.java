package com.bloxbean.cardano.yaci.core.protocol.localtx;

import com.bloxbean.cardano.yaci.core.protocol.localtx.messages.MsgRejectTx;
import com.bloxbean.cardano.yaci.core.protocol.localtx.model.TxSubmissionError;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for transaction submission error parsing.
 * Tests the complete flow from CBOR to user-friendly messages.
 */
class TxSubmissionErrorParsingIntegrationTest {
    
    @Test
    void testMsgRejectTxWithUnparsableCbor() {
        // Test with invalid CBOR that should fall back to original hex
        String invalidCbor = "invalid_hex";
        MsgRejectTx msgRejectTx = new MsgRejectTx(invalidCbor);
        
        // Should still return a valid error object
        TxSubmissionError error = msgRejectTx.getParsedError();
        assertThat(error).isNotNull();
        assertThat(error.getErrorCode()).isEqualTo("UNPARSED");
        assertThat(error.getOriginalCbor()).isEqualTo(invalidCbor);
        
        // Should provide a user-friendly message with fallback
        String userMessage = msgRejectTx.getUserFriendlyMessage();
        assertThat(userMessage).contains("Transaction rejected with error");
        assertThat(userMessage).contains(invalidCbor);
    }
    
    @Test
    void testErrorMessageCreation() {
        String cborHex = "invalid_cbor";
        
        // Test how LocalTxSubmissionClient would use the error
        MsgRejectTx msgRejectTx = new MsgRejectTx(cborHex);
        
        String userMessage = msgRejectTx.getUserFriendlyMessage();
        TxSubmissionError parsedError = msgRejectTx.getParsedError();
        
        assertThat(userMessage).isNotNull();
        assertThat(userMessage).contains("Transaction rejected with error");
        assertThat(parsedError).isNotNull();
        assertThat(parsedError.getDisplayMessage()).isEqualTo(userMessage);
    }
    
    @Test
    void testFallbackBehavior() {
        // Test that we always get a meaningful message, even with null/empty input
        
        // Test null CBOR
        MsgRejectTx nullMsg = new MsgRejectTx(null);
        assertThat(nullMsg.getUserFriendlyMessage()).isNotEmpty();
        assertThat(nullMsg.getParsedError()).isNotNull();
        
        // Test empty CBOR
        MsgRejectTx emptyMsg = new MsgRejectTx("");
        assertThat(emptyMsg.getUserFriendlyMessage()).isNotEmpty();
        assertThat(emptyMsg.getParsedError()).isNotNull();
        
        // Test whitespace CBOR
        MsgRejectTx whitespaceMsg = new MsgRejectTx("   ");
        assertThat(whitespaceMsg.getUserFriendlyMessage()).isNotEmpty();
        assertThat(whitespaceMsg.getParsedError()).isNotNull();
    }
    
    @Test
    void testErrorHierarchy() {
        String cborHex = "some_cbor";
        MsgRejectTx msgRejectTx = new MsgRejectTx(cborHex);
        
        TxSubmissionError error = msgRejectTx.getParsedError();
        
        // Test that we get a base TxSubmissionError
        assertThat(error).isInstanceOf(TxSubmissionError.class);
        
        // Test that the error has required fields
        assertThat(error.getErrorCode()).isNotNull();
        assertThat(error.getOriginalCbor()).isEqualTo(cborHex);
        assertThat(error.getDisplayMessage()).isNotNull();
    }
    
    @Test
    void testLazyParsingPerformance() {
        String cborHex = "test_cbor";
        MsgRejectTx msgRejectTx = new MsgRejectTx(cborHex);
        
        // First access should parse
        long start1 = System.nanoTime();
        TxSubmissionError error1 = msgRejectTx.getParsedError();
        long time1 = System.nanoTime() - start1;
        
        // Second access should use cached result
        long start2 = System.nanoTime();
        TxSubmissionError error2 = msgRejectTx.getParsedError();
        long time2 = System.nanoTime() - start2;
        
        // Should be the same object (cached)
        assertThat(error1).isSameAs(error2);
        
        // Second access should be significantly faster (cached)
        assertThat(time2).isLessThan(time1);
    }
    
    @Test
    void testOriginalCborFallbackMessage() {
        String originalCbor = "82020418641234";
        MsgRejectTx msgRejectTx = new MsgRejectTx(originalCbor);
        
        TxSubmissionError error = msgRejectTx.getParsedError();
        
        // Even if parsing fails, should have the original CBOR for reference
        assertThat(error.getOriginalCbor()).isEqualTo(originalCbor);
        
        // Display message should include the original CBOR as fallback
        String displayMessage = error.getDisplayMessage();
        assertThat(displayMessage).isNotNull();
        assertThat(displayMessage).isNotEmpty();
    }
}