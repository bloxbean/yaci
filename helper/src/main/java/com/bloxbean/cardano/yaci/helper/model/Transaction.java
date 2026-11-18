package com.bloxbean.cardano.yaci.helper.model;

import com.bloxbean.cardano.yaci.core.model.AuxData;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.Witnesses;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {
    private long blockNumber;
    private long slot;
    private String txHash;
    private String txCbor;
    private TransactionBody body;
    /**
     * The outputs of the top-level transaction only. A Dijkstra sub-transaction's outputs are not listed: when the
     * top-level transaction is valid, each sub-transaction spends its inputs and creates its outputs under its own id,
     * and a listener applies both from {@code body.getSubTransactions()} (ADR 0003 D4b).
     */
    private List<Utxo> utxos;
    private Utxo collateralReturnUtxo;
    private Witnesses witnesses;
    private AuxData auxData;
    private boolean invalid;
}
