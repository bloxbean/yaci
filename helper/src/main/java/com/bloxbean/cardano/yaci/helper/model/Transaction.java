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
    private TransactionBody body;
    private List<Utxo> utxos;
    private Utxo collateralReturnUtxo;
    private Witnesses witnesses;
    private AuxData auxData;
    private boolean invalid;
    private int txSize;
    private int txScriptSize;
}
