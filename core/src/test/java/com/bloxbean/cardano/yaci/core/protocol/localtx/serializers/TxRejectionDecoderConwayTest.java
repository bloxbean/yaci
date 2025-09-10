package com.bloxbean.cardano.yaci.core.protocol.localtx.serializers;

import com.bloxbean.cardano.yaci.core.protocol.localtx.model.ConwayUtxoErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test Conway era error code mappings.
 */
class TxRejectionDecoderConwayTest {

    @BeforeEach
    void setUp() {
        // No setup needed for enum tests
    }

    @Test
    void testConwayErrorCodeEnum() {
        // Verify that Conway error codes are correctly mapped
        
        // Test code 0 = UtxosFailure (different from previous eras)
        ConwayUtxoErrorCode error0 = ConwayUtxoErrorCode.fromCode(0);
        assertThat(error0).isNotNull();
        assertThat(error0.getConstructorName()).isEqualTo("UtxosFailure");
        assertThat(error0.getPreviousEraEquivalent()).contains("Code 7 in Alonzo/Babbage");
        
        // Test code 1 = BadInputsUTxO (different from previous eras)
        ConwayUtxoErrorCode error1 = ConwayUtxoErrorCode.fromCode(1);
        assertThat(error1).isNotNull();
        assertThat(error1.getConstructorName()).isEqualTo("BadInputsUTxO");
        assertThat(error1.getPreviousEraEquivalent()).contains("Code 0 in Shelley/Alonzo/Babbage");
        
        // Test code 6 = ValueNotConservedUTxO (was OutputTooSmallUTxO in previous eras)
        ConwayUtxoErrorCode error6 = ConwayUtxoErrorCode.fromCode(6);
        assertThat(error6).isNotNull();
        assertThat(error6.getConstructorName()).isEqualTo("ValueNotConservedUTxO");
        assertThat(error6.getPreviousEraEquivalent()).contains("Code 5 in Shelley/Alonzo/Babbage");
        
        // Test code 9 = OutputTooSmallUTxO (was WrongNetworkWithdrawal in previous eras)
        ConwayUtxoErrorCode error9 = ConwayUtxoErrorCode.fromCode(9);
        assertThat(error9).isNotNull();
        assertThat(error9.getConstructorName()).isEqualTo("OutputTooSmallUTxO");
        
        // Test new Conway-specific codes 21 and 22 (inherited from Babbage)
        ConwayUtxoErrorCode error21 = ConwayUtxoErrorCode.fromCode(21);
        assertThat(error21).isNotNull();
        assertThat(error21.getConstructorName()).isEqualTo("BabbageOutputTooSmallUTxO");
        assertThat(error21.isBabbageInheritedError()).isTrue();
        
        ConwayUtxoErrorCode error22 = ConwayUtxoErrorCode.fromCode(22);
        assertThat(error22).isNotNull();
        assertThat(error22.getConstructorName()).isEqualTo("BabbageNonDisjointRefInputs");
        assertThat(error22.isBabbageInheritedError()).isTrue();
        
        // Test that all Conway codes (0-22) are valid
        assertThat(ConwayUtxoErrorCode.isValidConwayCode(0)).isTrue();
        assertThat(ConwayUtxoErrorCode.isValidConwayCode(22)).isTrue();
        assertThat(ConwayUtxoErrorCode.isValidConwayCode(30)).isFalse(); // Beyond range
        
        // Test collateral error detection
        assertThat(ConwayUtxoErrorCode.INSUFFICIENT_COLLATERAL.isCollateralError()).isTrue();
        assertThat(ConwayUtxoErrorCode.BAD_INPUTS_UTXO.isCollateralError()).isFalse();
        
        // Test script error detection
        assertThat(ConwayUtxoErrorCode.SCRIPTS_NOT_PAID_UTXO.isScriptError()).isTrue();
        assertThat(ConwayUtxoErrorCode.EX_UNITS_TOO_BIG_UTXO.isScriptError()).isTrue();
        assertThat(ConwayUtxoErrorCode.BAD_INPUTS_UTXO.isScriptError()).isFalse();
    }
    
    @Test
    void testConwayVsPreviousEras() {
        // Show the key differences between Conway and previous eras
        
        // Code 0: BadInputsUTxO in old eras → UtxosFailure in Conway
        assertThat(ConwayUtxoErrorCode.fromCode(0).getConstructorName()).isEqualTo("UtxosFailure");
        
        // Code 1: ExpiredUTxO/OutsideValidityInterval in old eras → BadInputsUTxO in Conway  
        assertThat(ConwayUtxoErrorCode.fromCode(1).getConstructorName()).isEqualTo("BadInputsUTxO");
        
        // Code 6: OutputTooSmallUTxO in old eras → ValueNotConservedUTxO in Conway
        assertThat(ConwayUtxoErrorCode.fromCode(6).getConstructorName()).isEqualTo("ValueNotConservedUTxO");
        
        // Code 9: WrongNetworkWithdrawal in old eras → OutputTooSmallUTxO in Conway
        assertThat(ConwayUtxoErrorCode.fromCode(9).getConstructorName()).isEqualTo("OutputTooSmallUTxO");
        
        // This demonstrates that Conway era completely restructured the error code mappings
    }
}