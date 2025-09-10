package com.bloxbean.cardano.yaci.core.protocol.localtx.serializers;

import com.bloxbean.cardano.yaci.core.protocol.localtx.model.TxSubmissionError;
import com.bloxbean.cardano.yaci.core.protocol.localtx.model.UTxOError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for era-specific error code parsing in TxRejectionDecoder.
 * Verifies that error codes are correctly mapped according to cardano-ledger Haskell source.
 */
class TxRejectionDecoderEraTest {

    private TxRejectionDecoder decoder;

    @BeforeEach
    void setUp() {
        decoder = new TxRejectionDecoder();
    }

    @Test
    void testShelleyErrorCode6_OutputTooSmallUTxO() {
        // Simple Shelley-style error wrapped: [2, [6, []]]
        // CBOR: [2, [6, []]] = 820282068[]
        String shelleyError6Cbor = "82028206f6"; // [2, [6, true]]
        
        TxSubmissionError result = decoder.decode(shelleyError6Cbor);
        
        assertThat(result.getErrorCode()).isEqualTo("OutputTooSmallUTxO");
        assertThat(result.getUserMessage()).contains("outputs are below minimum");
    }

    @Test
    void testAlonzoErrorCode6_OutputTooSmallUTxO() {
        // Alonzo-style error with more context: [6, [...]]
        String alonzoError6Cbor = "82068201820182"; // [6, [[1, []]]]
        
        TxSubmissionError result = decoder.decode(alonzoError6Cbor);
        
        assertThat(result).isInstanceOf(UTxOError.class);
        UTxOError utxoError = (UTxOError) result;
        assertThat(utxoError.getErrorCode()).isEqualTo("OutputTooSmallUTxO");
        assertThat(utxoError.getUserMessage()).contains("outputs are too small");
    }

    @Test
    void testBabbageWrappedError_Code1WithAlonzoCode6() {
        // Babbage structure: [1, <alonzo_error>] where alonzo_error is [6, [...]]
        String babbageWrappedCbor = "82018206820180"; // [1, [6, [[], 0]]]
        
        TxSubmissionError result = decoder.decode(babbageWrappedCbor);
        
        assertThat(result).isInstanceOf(UTxOError.class);
        UTxOError utxoError = (UTxOError) result;
        assertThat(utxoError.getErrorCode()).isEqualTo("OutputTooSmallUTxO");
        assertThat(utxoError.getDetails()).containsEntry("era", "Babbage-wrapped-Alonzo");
        assertThat(utxoError.getDetails()).containsEntry("babbageWrapper", "AlonzoInBabbageUtxoPredFailure");
    }

    @Test
    void testRealWorldComplexCBOR() {
        // This is the actual CBOR from the conversation that was parsing as "UNKNOWN"
        // Structure: [2, [[6, [complex_nested_data]]]]
        String realCbor = "820281820682820182008201d90102818258204d77bc019ede3afd35785a801693c3e96a85c2d6ea05e8a5b2b225bc947c9e3f01820182008306001b00000002537ade61";
        
        TxSubmissionError result = decoder.decode(realCbor);
        
        // The key requirement: it should not return "UNKNOWN" anymore
        assertThat(result.getErrorCode()).doesNotContain("UNKNOWN");
        assertThat(result.getErrorCode()).isEqualTo("OutputTooSmallUTxO");
        
        // Should provide meaningful error message
        assertThat(result.getUserMessage()).isNotBlank();
        assertThat(result.getUserMessage()).doesNotContain("Transaction rejected with error:");
    }

    @Test
    void testAlonzoScriptError_Code14() {
        // Test Alonzo-specific script error: ScriptsNotPaidUTxO (code 14)
        String alonzoScriptErrorCbor = "820e80"; // [14, []]
        
        TxSubmissionError result = decoder.decode(alonzoScriptErrorCbor);
        
        assertThat(result.getErrorCode()).isEqualTo("ScriptsNotPaidUTxO");
        assertThat(result.getUserMessage()).contains("Script addresses in collateral inputs are not allowed");
    }

    @Test
    void testAlonzoCollateralError_Code16() {
        // Test Alonzo-specific collateral error: CollateralContainsNonADA (code 16)
        String alonzoCollateralCbor = "821080"; // [16, []]
        
        TxSubmissionError result = decoder.decode(alonzoCollateralCbor);
        
        assertThat(result.getErrorCode()).isEqualTo("CollateralContainsNonADA");
        assertThat(result.getUserMessage()).contains("Collateral inputs contain non-ADA tokens");
    }

    @Test
    void testBabbageSpecificError_Code3() {
        // Test Babbage-specific error: BabbageOutputTooSmallUTxO (code 3)
        String babbageSpecificCbor = "820380"; // [3, []]
        
        TxSubmissionError result = decoder.decode(babbageSpecificCbor);
        
        assertThat(result.getErrorCode()).isEqualTo("BabbageOutputTooSmallUTxO");
        assertThat(result.getUserMessage()).contains("Output value is below minimum UTxO requirement (Babbage calculation)");
    }

    @Test
    void testShelleyBasicErrors() {
        // Test basic Shelley errors that are inherited in later eras
        
        // BadInputsUTxO (code 0)
        String badInputsCbor = "820080"; // [0, []]
        TxSubmissionError badInputsResult = decoder.decode(badInputsCbor);
        assertThat(badInputsResult.getErrorCode()).isEqualTo("BadInputsUTxO");
        
        // FeeTooSmallUTxO (code 4) 
        String feeTooSmallCbor = "820480"; // [4, []]
        TxSubmissionError feeTooSmallResult = decoder.decode(feeTooSmallCbor);
        assertThat(feeTooSmallResult.getErrorCode()).isEqualTo("FeeTooSmallUTxO");
        
        // ValueNotConservedUTxO (code 5)
        String valueNotConservedCbor = "820580"; // [5, []]  
        TxSubmissionError valueNotConservedResult = decoder.decode(valueNotConservedCbor);
        assertThat(valueNotConservedResult.getErrorCode()).isEqualTo("ValueNotConservedUTxO");
    }

    @Test
    void testConwayEraErrorCodes() {
        // Conway era has completely different mappings - test key differences
        
        // Conway code 0 = UtxosFailure (was BadInputsUTxO in previous eras)
        String conwayCode0 = "820080"; // [0, []]
        TxSubmissionError result0 = decoder.decode(conwayCode0);
        assertThat(result0.getErrorCode()).isEqualTo("UtxosFailure");
        assertThat(result0.getDetails()).containsEntry("possibleEra", "Conway");
        assertThat(result0.getDetails()).containsEntry("previousEraEquivalent", "Code 7 in Alonzo/Babbage");
        
        // Conway code 1 = BadInputsUTxO (was ExpiredUTxO/OutsideValidityIntervalUTxO)
        String conwayCode1 = "820180"; // [1, []]
        TxSubmissionError result1 = decoder.decode(conwayCode1);
        assertThat(result1.getErrorCode()).isEqualTo("BadInputsUTxO");
        assertThat(result1.getDetails()).containsEntry("possibleEra", "Conway");
        assertThat(result1.getDetails()).containsEntry("previousEraEquivalent", "Code 0 in Shelley/Alonzo/Babbage");
        
        // Conway code 6 = ValueNotConservedUTxO (was OutputTooSmallUTxO in previous eras)
        String conwayCode6 = "820680"; // [6, []]
        TxSubmissionError result6 = decoder.decode(conwayCode6);
        assertThat(result6.getErrorCode()).isEqualTo("ValueNotConservedUTxO");
        assertThat(result6.getDetails()).containsEntry("possibleEra", "Conway");
        assertThat(result6.getDetails()).containsEntry("previousEraEquivalent", "Code 5 in Shelley/Alonzo/Babbage");
        
        // Conway code 9 = OutputTooSmallUTxO (was WrongNetworkWithdrawal in previous eras)
        String conwayCode9 = "820980"; // [9, []]
        TxSubmissionError result9 = decoder.decode(conwayCode9);
        assertThat(result9.getErrorCode()).isEqualTo("OutputTooSmallUTxO");
        assertThat(result9.getDetails()).containsEntry("possibleEra", "Conway");
        
        // Conway code 21 = BabbageOutputTooSmallUTxO (new in Conway, inherited from Babbage)
        String conwayCode21 = "821580"; // [21, []]
        TxSubmissionError result21 = decoder.decode(conwayCode21);
        assertThat(result21.getErrorCode()).isEqualTo("BabbageOutputTooSmallUTxO");
        assertThat(result21.getDetails()).containsEntry("possibleEra", "Conway");
        assertThat(result21.getDetails()).containsEntry("previousEraEquivalent", "Code 3 in Babbage era");
        
        // Conway code 22 = BabbageNonDisjointRefInputs (new in Conway, inherited from Babbage)
        String conwayCode22 = "821680"; // [22, []]
        TxSubmissionError result22 = decoder.decode(conwayCode22);
        assertThat(result22.getErrorCode()).isEqualTo("BabbageNonDisjointRefInputs");
        assertThat(result22.getDetails()).containsEntry("possibleEra", "Conway");
        assertThat(result22.getDetails()).containsEntry("previousEraEquivalent", "Code 4 in Babbage era");
    }

    @Test
    void testUnknownErrorCode() {
        // Test handling of unknown error codes
        String unknownErrorCbor = "821e80"; // [30, []] - code 30 doesn't exist
        
        TxSubmissionError result = decoder.decode(unknownErrorCbor);
        
        assertThat(result.getErrorCode()).isEqualTo("UNKNOWN_ERROR_30");
        assertThat(result.getUserMessage()).contains("Unknown transaction validation error");
    }

    @Test
    void testInvalidCBOR() {
        // Test handling of invalid CBOR
        String invalidCbor = "invalid_hex";
        
        TxSubmissionError result = decoder.decode(invalidCbor);
        
        assertThat(result.getErrorCode()).isEqualTo("UNPARSED");
        assertThat(result.getUserMessage()).contains("Transaction rejected with error");
        assertThat(result.getOriginalCbor()).isEqualTo(invalidCbor);
    }
}