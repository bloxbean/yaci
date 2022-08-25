package com.bloxbean.cardano.yaci.core.model;

import lombok.*;

import java.util.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder
public class Block {
    private Era era;
    private BlockHeader header;

    @Builder.Default
    private List<TransactionBody> transactionBodies = new ArrayList<>();

    private List<Witnesses> transactionWitness = new ArrayList<>();
    private Map<Integer, AuxData> auxiliaryDataMap = new LinkedHashMap();
    private List<Integer> invalidTransactions = new ArrayList<>();
}
