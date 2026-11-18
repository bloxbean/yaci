package com.bloxbean.cardano.yaci.helper.model;

import com.bloxbean.cardano.yaci.core.model.Amount;
import com.fasterxml.jackson.annotation.JsonInclude;
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
    /** Blake2b-256 of the inline datum's bytes; null without an inline datum. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String inlineDatumHash;
    /** Hash of the reference script, from the bytes received; null without one or when its bytes are not a script. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String referenceScriptHash;

    /** Kept for source and binary compatibility: the hashes are null. Use the builder for them. */
    public Utxo(String txHash, int index, String address, List<Amount> amounts, String datumHash, String inlineDatum,
                String scriptRef) {
        this(txHash, index, address, amounts, datumHash, inlineDatum, scriptRef, null, null);
    }
}
