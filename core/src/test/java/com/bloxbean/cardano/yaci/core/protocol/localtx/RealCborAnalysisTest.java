package com.bloxbean.cardano.yaci.core.protocol.localtx;

import com.bloxbean.cardano.yaci.core.protocol.localtx.serializers.TxRejectionDecoder;
import com.bloxbean.cardano.yaci.core.protocol.localtx.model.TxSubmissionError;
import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import co.nstant.in.cbor.model.ByteString;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

/**
 * Test to analyze real CBOR rejection messages from Cardano nodes
 */
public class RealCborAnalysisTest {
    
    @Test
    void analyzeRealRejectionMessage() throws Exception {
        // Real rejection CBOR from the test
        String realCbor = "820281820682820182008201d90102818258204d77bc019ede3afd35785a801693c3e96a85c2d6ea05e8a5b2b225bc947c9e3f01820182008306001b00000002537ade61";
        
        System.out.println("=== Analyzing Real CBOR Rejection Message ===");
        System.out.println("CBOR: " + realCbor);
        
        System.out.println("\n=== Expected Errors from Blockfrost ===");
        System.out.println("1. BadInputsUTxO - TxIn 4d77bc019ede3afd35785a801693c3e96a85c2d6ea05e8a5b2b225bc947c9e3f:1");
        System.out.println("2. ValueNotConservedUTxO - Expected 9,990,495,841 Lovelace but got 0");
        
        try {
            byte[] cborBytes = HexUtil.decodeHexString(realCbor);
            ByteArrayInputStream bais = new ByteArrayInputStream(cborBytes);
            CborDecoder decoder = new CborDecoder(bais);
            List<DataItem> dataItems = decoder.decode();
            
            System.out.println("\n=== CBOR Structure Analysis ===");
            System.out.println("Number of root items: " + dataItems.size());
            
            if (!dataItems.isEmpty()) {
                DataItem rootItem = dataItems.get(0);
                System.out.println("Root item type: " + rootItem.getClass().getSimpleName());
                
                // Let's decode this step by step
                analyzeArrayStructure(rootItem, 0);
                
                // Try to parse with our decoder
                TxRejectionDecoder rejectionDecoder = new TxRejectionDecoder();
                TxSubmissionError error = rejectionDecoder.decode(realCbor);
                
                System.out.println("\n=== Our Parser Results ===");
                System.out.println("Error code: " + error.getErrorCode());
                System.out.println("User message: " + error.getUserMessage());
                System.out.println("Display message: " + error.getDisplayMessage());
                
                if (error.getDetails() != null) {
                    System.out.println("Details: " + error.getDetails());
                }
                
                System.out.println("\n=== Discrepancy Analysis ===");
                System.out.println("Our parser found: " + error.getErrorCode());
                System.out.println("Blockfrost reports: BadInputsUTxO + ValueNotConservedUTxO");
                System.out.println("This suggests the CBOR might contain multiple errors or we're misinterpreting the structure");
            }
            
        } catch (Exception e) {
            System.err.println("Failed to analyze CBOR: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void analyzeArrayStructure(DataItem item, int depth) {
        String indent = "  ".repeat(depth);
        
        if (item instanceof co.nstant.in.cbor.model.Array) {
            co.nstant.in.cbor.model.Array array = (co.nstant.in.cbor.model.Array) item;
            System.out.println(indent + "Array[" + array.getDataItems().size() + "]:");
            
            for (int i = 0; i < array.getDataItems().size(); i++) {
                DataItem child = array.getDataItems().get(i);
                System.out.println(indent + "  [" + i + "] " + child.getClass().getSimpleName() + ": " + 
                    (child instanceof UnsignedInteger ? ((UnsignedInteger) child).getValue() : 
                     child instanceof ByteString ? "ByteString(length=" + ((ByteString) child).getBytes().length + ")" :
                     child.getClass().getSimpleName()));
                
                if (child instanceof co.nstant.in.cbor.model.Array) {
                    analyzeArrayStructure(child, depth + 1);
                }
            }
        }
    }
    
    @Test
    void testCurrentParser() {
        String realCbor = "820281820682820182008201d90102818258204d77bc019ede3afd35785a801693c3e96a85c2d6ea05e8a5b2b225bc947c9e3f01820182008306001b00000002537ade61";
        
        TxRejectionDecoder decoder = new TxRejectionDecoder();
        TxSubmissionError error = decoder.decode(realCbor);
        
        System.out.println("Current parser result: " + error.getDisplayMessage());
        
        // This should at least give us the fallback message with CBOR
        assert error.getDisplayMessage().contains("Transaction rejected");
    }
}