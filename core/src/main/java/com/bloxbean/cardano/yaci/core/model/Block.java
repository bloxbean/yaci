package com.bloxbean.cardano.yaci.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.util.*;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder(toBuilder = true)
public class Block {
    private Era era;
    private BlockHeader header;

    @Builder.Default
    private List<TransactionBody> transactionBodies = new ArrayList<>();

    private List<Witnesses> transactionWitness = new ArrayList<>();
    private Map<Integer, AuxData> auxiliaryDataMap = new LinkedHashMap();
    private List<Integer> invalidTransactions = new ArrayList<>();

    private String cbor;

    /**
     * The Leios certificate of a Dijkstra block, or null if it has none.
     * <p>
     * Experimental: Dijkstra is not final; this may change in any release.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private LeiosCertificate leiosCertificate;

    /**
     * The Peras certificate of a Dijkstra block, the content of its byte string in hex, or null if it has none.
     * <p>
     * Experimental: Dijkstra is not final; this may change in any release.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String perasCertificate;

    /** Kept for source and binary compatibility: the Dijkstra fields are null. Use the builder for them. */
    public Block(Era era, BlockHeader header, List<TransactionBody> transactionBodies,
                 List<Witnesses> transactionWitness, Map<Integer, AuxData> auxiliaryDataMap,
                 List<Integer> invalidTransactions, String cbor) {
        this(era, header, transactionBodies, transactionWitness, auxiliaryDataMap, invalidTransactions, cbor, null,
                null);
    }
}
