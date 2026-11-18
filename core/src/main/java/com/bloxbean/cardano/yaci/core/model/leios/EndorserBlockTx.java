package com.bloxbean.cardano.yaci.core.model.leios;

import com.bloxbean.cardano.yaci.core.model.Era;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * One transaction-list item fetched for a Leios Endorser Block.
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder(toBuilder = true)
public class EndorserBlockTx {
    private int index;
    private int txEraIndex;
    private Era era;
    private String txCbor;
    private String txHash;
    private String cbor;
    private boolean parsed;
}
