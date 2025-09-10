package com.bloxbean.cardano.yaci.core.protocol.localtx.messages;

import com.bloxbean.cardano.yaci.core.protocol.localtx.model.TxSubmissionError;
import com.bloxbean.cardano.yaci.core.protocol.localtx.serializers.TxRejectionDecoder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MsgRejectTxTest {
    
    @Test
    void testGetParsedError() {
        // Create a MsgRejectTx with an error CBOR
        String cborHex = "820204";
        MsgRejectTx msgRejectTx = new MsgRejectTx(cborHex, null, new TxRejectionDecoder());
        
        TxSubmissionError error = msgRejectTx.getParsedError();
        
        assertThat(error).isNotNull();
        assertThat(error.getErrorCode()).isNotNull();
        assertThat(error.getOriginalCbor()).isEqualTo(cborHex);
    }
    
    @Test
    void testGetUserFriendlyMessage() {
        // Create a MsgRejectTx with an error CBOR
        String cborHex = "820200";
        MsgRejectTx msgRejectTx = new MsgRejectTx(cborHex, null, new TxRejectionDecoder());
        
        String message = msgRejectTx.getUserFriendlyMessage();
        
        assertThat(message).isNotNull();
        assertThat(message).isNotEmpty();
    }
    
    @Test
    void testGetUserFriendlyMessageWithUnparseable() {
        // Create a MsgRejectTx with unparseable CBOR
        String cborHex = "invalid_cbor";
        MsgRejectTx msgRejectTx = new MsgRejectTx(cborHex, null, new TxRejectionDecoder());
        
        String message = msgRejectTx.getUserFriendlyMessage();
        
        assertThat(message).isNotNull();
        assertThat(message).contains("Transaction rejected with error");
        assertThat(message).contains(cborHex);
    }
    
    @Test
    void testLazyParsing() {
        // Create a MsgRejectTx
        String cborHex = "820204";
        MsgRejectTx msgRejectTx = new MsgRejectTx(cborHex, null, new TxRejectionDecoder());
        
        // First call should parse
        TxSubmissionError error1 = msgRejectTx.getParsedError();
        // Second call should return cached result
        TxSubmissionError error2 = msgRejectTx.getParsedError();
        
        assertThat(error1).isSameAs(error2);
    }
    
    @Test
    void testWithNullCbor() {
        MsgRejectTx msgRejectTx = new MsgRejectTx(null, null, new TxRejectionDecoder());
        
        TxSubmissionError error = msgRejectTx.getParsedError();
        String message = msgRejectTx.getUserFriendlyMessage();
        
        assertThat(error).isNotNull();
        assertThat(error.getErrorCode()).isEqualTo("UNPARSED");
        assertThat(message).contains("Transaction rejected");
    }
}