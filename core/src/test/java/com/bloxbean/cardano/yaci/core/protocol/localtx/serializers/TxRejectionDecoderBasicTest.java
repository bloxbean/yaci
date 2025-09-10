package com.bloxbean.cardano.yaci.core.protocol.localtx.serializers;

import com.bloxbean.cardano.yaci.core.protocol.localtx.model.TxSubmissionError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Basic test to verify TxRejectionDecoder functionality with the real CBOR example.
 */
class TxRejectionDecoderBasicTest {

    private TxRejectionDecoder decoder;

    @BeforeEach
    void setUp() {
        decoder = new TxRejectionDecoder();
    }

    @Test
    void testRealCborShouldNotReturnUnknown() {
        // This was the problematic CBOR that returned "UNKNOWN" before our fix
        String realCbor = "820281820682820182008201d90102818258204d77bc019ede3afd35785a801693c3e96a85c2d6ea05e8a5b2b225bc947c9e3f01820182008306001b00000002537ade61";
        
        TxSubmissionError result = decoder.decode(realCbor);
        
        // The main fix: should not return "UNKNOWN" error code anymore
        assertThat(result.getErrorCode()).doesNotContain("UNKNOWN");
        
        // Should return a proper error code from our era-specific mappings
        assertThat(result.getErrorCode()).isIn("OutputTooSmallUTxO", "BadInputsUTxO", "ValueNotConservedUTxO", "UtxosFailure");
        
        // Should have meaningful message, not generic fallback
        assertThat(result.getUserMessage()).isNotEqualTo("Transaction rejected with error: " + realCbor);
        
        // Should preserve original CBOR for debugging
        assertThat(result.getOriginalCbor()).isEqualTo(realCbor);
    }

    @Test 
    void testInvalidCborHandling() {
        String invalidCbor = "invalid";
        
        TxSubmissionError result = decoder.decode(invalidCbor);
        
        assertThat(result.getErrorCode()).isEqualTo("UNPARSED");
        assertThat(result.getUserMessage()).contains("Transaction rejected with error");
        assertThat(result.getOriginalCbor()).isEqualTo(invalidCbor);
    }

    @Test
    void testEmptyCborHandling() {
        String emptyCbor = "";
        
        TxSubmissionError result = decoder.decode(emptyCbor);
        
        assertThat(result.getErrorCode()).isEqualTo("UNPARSED");
        assertThat(result.getUserMessage()).contains("Transaction rejected with error: empty");
    }
}