package com.bloxbean.cardano.yaci.core.model.leios;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Transaction hash and advertised size entry from a Leios Endorser Block.
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder(toBuilder = true)
public class EndorserBlockTxRef {
    private String txHash;
    private long txSize;
}
