package com.bloxbean.cardano.yaci.helper.model;

import com.bloxbean.cardano.yaci.core.model.Amount;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Utxo {
    private String txHash;
    private int index;
    private String address;
    private List<Amount> amounts;
    private String datumHash;
    private String inlineDatum;
    private String scriptRef;
    /** Reference-script hash, or null when absent or its encoding cannot be interpreted. */
    private String scriptHash;

    public Utxo(String txHash, int index, String address, List<Amount> amounts,
                String datumHash, String inlineDatum, String scriptRef) {
        this(txHash, index, address, amounts, datumHash, inlineDatum, scriptRef, null);
    }
}
