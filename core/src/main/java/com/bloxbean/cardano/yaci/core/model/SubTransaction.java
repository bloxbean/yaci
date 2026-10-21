package com.bloxbean.cardano.yaci.core.model;

import lombok.*;

/**
 * Dijkstra era: a sub-transaction (tx body key 23),
 * {@code [sub_transaction_body, transaction_witness_set, auxiliary_data / nil]}.
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder(toBuilder = true)
@EqualsAndHashCode
@ToString
public class SubTransaction {
    //Sub-transaction body, with its own txHash (blake2b-256 of the body bytes as on chain)
    private TransactionBody body;
    private Witnesses witnesses;
    //null when nil
    private AuxData auxData;
}
