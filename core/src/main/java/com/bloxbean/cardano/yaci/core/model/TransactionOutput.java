package com.bloxbean.cardano.yaci.core.model;

import lombok.*;

import java.util.List;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder
public class TransactionOutput {
    private String address;
    private List<Amount> amounts;
    private String datumHash;

    //transaction_output = [address, amount : value, ? datum_hash : $hash32]

    //babbage
    private String inlineDatum;
    private String scriptRef;
    /** Reference-script hash, or null when absent or its encoding cannot be interpreted. */
    private String scriptHash;

    public TransactionOutput(String address, List<Amount> amounts, String datumHash,
                             String inlineDatum, String scriptRef) {
        this(address, amounts, datumHash, inlineDatum, scriptRef, null);
    }
}
