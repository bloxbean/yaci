package com.bloxbean.cardano.yaci.core.model.leios;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * The endorser block (EB) a ranking block certifies, set on certifying blocks delivered with their EB transactions
 * inlined (see {@code YaciConfig.isInlineEndorserBlockTxs()}). The block's first {@code txCount} transactions
 * came from this EB, in EB order; any transactions after them are the block's own.
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder(toBuilder = true)
public class LeiosEndorserBlockRef {
    private String ebHash;
    private long ebSlot;
    private long ebSize;
    private int txCount;
}
