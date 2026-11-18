package com.bloxbean.cardano.yaci.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
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
    /** The inline datum as received: the bytes inside its tag 24. */
    private String inlineDatum;
    /** The reference script as received: the bytes inside its tag 24. */
    private String scriptRef;
    /**
     * Blake2b-256 of {@link #inlineDatum}'s bytes, as the ledger hashes them; null without an inline datum. The bytes are
     * not checked to be Plutus data: a block on chain has passed the ledger's rules.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String inlineDatumHash;
    /**
     * Hash of the reference script, as the ledger computes it from the bytes received; null without a reference script
     * or when they are not {@code [type, script]} of a known type. The script itself is not checked against the ledger's
     * rules, which a block on chain has passed.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String referenceScriptHash;

    /** Kept for source and binary compatibility: the hashes are null. Use the builder for them. */
    public TransactionOutput(String address, List<Amount> amounts, String datumHash, String inlineDatum,
                             String scriptRef) {
        this(address, amounts, datumHash, inlineDatum, scriptRef, null, null);
    }
}
