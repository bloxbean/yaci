package com.bloxbean.cardano.yaci.core.protocol.localtx.serializers;

import com.bloxbean.cardano.yaci.core.protocol.localtx.model.TxSubmissionError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TxRejectionDecoderTest {
    
    private TxRejectionDecoder decoder;
    
    @BeforeEach
    void setUp() {
        decoder = new TxRejectionDecoder();
    }
    
    @Test
    void testDecodeEmptyCbor() {
        TxSubmissionError error = decoder.decode("");
        
        assertThat(error).isNotNull();
        assertThat(error.getErrorCode()).isEqualTo("UNPARSED");
        assertThat(error.getOriginalCbor()).isEqualTo("empty");
    }
    
    @Test
    void testDecodeNullCbor() {
        TxSubmissionError error = decoder.decode(null);
        
        assertThat(error).isNotNull();
        assertThat(error.getErrorCode()).isEqualTo("UNPARSED");
        assertThat(error.getOriginalCbor()).isEqualTo("empty");
    }
    
    @Test
    void testDecodeInvalidCbor() {
        // Invalid hex string
        TxSubmissionError error = decoder.decode("not_valid_hex");
        
        assertThat(error).isNotNull();
        assertThat(error.getErrorCode()).isEqualTo("UNPARSED");
        assertThat(error.getOriginalCbor()).isEqualTo("not_valid_hex");
    }
    
    @Test
    void testDecodeValidCborFallsBackIfNotRecognized() {
        // Valid CBOR that we don't recognize as a known error format
        String cborHex = "820204";
        
        TxSubmissionError error = decoder.decode(cborHex);
        
        assertThat(error).isNotNull();
        assertThat(error.getErrorCode()).isNotNull();
        assertThat(error.getOriginalCbor()).isEqualTo(cborHex);
        assertThat(error.getDisplayMessage()).isNotNull();
    }
    
    @Test
    void testDecodeNestedErrorStructure() {
        // CBOR array with nested structure - should fall back gracefully
        String cborHex = "82028200646461746";
        
        TxSubmissionError error = decoder.decode(cborHex);
        
        assertThat(error).isNotNull();
        assertThat(error.getOriginalCbor()).isEqualTo(cborHex);
    }
    
    @Test
    void testFallbackToOriginalCbor() {
        // Complex CBOR that might not be fully parseable
        String complexCbor = "8402A1636B65796576616C7565820383010203";
        
        TxSubmissionError error = decoder.decode(complexCbor);
        
        assertThat(error).isNotNull();
        assertThat(error.getOriginalCbor()).isEqualTo(complexCbor);
        // Should always have a display message, even if unparsed
        assertThat(error.getDisplayMessage()).isNotEmpty();
    }
    
    @Test
    void testErrorDisplayMessage() {
        // Test that all errors have a display message
        String cborHex = "820204";
        
        TxSubmissionError error = decoder.decode(cborHex);
        
        assertThat(error).isNotNull();
        assertThat(error.getDisplayMessage()).isNotEmpty();
        assertThat(error.getDisplayMessage()).doesNotContain("null");
    }
    
    @Test
    void testUnparsedError() {
        TxSubmissionError error = TxSubmissionError.unparsed("abc123");
        
        assertThat(error).isNotNull();
        assertThat(error.getErrorCode()).isEqualTo("UNPARSED");
        assertThat(error.getOriginalCbor()).isEqualTo("abc123");
        assertThat(error.getDisplayMessage()).contains("abc123");
    }
    
    @Test
    void testWhitespaceHandling() {
        TxSubmissionError error = decoder.decode("   ");
        
        assertThat(error).isNotNull();
        assertThat(error.getErrorCode()).isEqualTo("UNPARSED");
        assertThat(error.getOriginalCbor()).isEqualTo("empty");
    }
}