package com.bloxbean.cardano.yaci.core.model;

import com.bloxbean.cardano.yaci.core.model.leios.LeiosCertificate;
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

    private LeiosCertificate leiosCertificate;
    private String perasCertCbor;
    private String cbor;

    /**
     * Pre-Leios constructor, kept so existing callers compile and behave unchanged.
     */
    public Block(Era era, BlockHeader header, List<TransactionBody> transactionBodies,
                 List<Witnesses> transactionWitness, Map<Integer, AuxData> auxiliaryDataMap,
                 List<Integer> invalidTransactions, String cbor) {
        this(era, header, transactionBodies, transactionWitness, auxiliaryDataMap, invalidTransactions,
                null, null, cbor);
    }
}
