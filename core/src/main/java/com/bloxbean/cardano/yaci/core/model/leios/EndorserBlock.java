package com.bloxbean.cardano.yaci.core.model.leios;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

/**
 * Decoded Leios Endorser Block transaction-reference map with its raw CBOR and advisory computed hash.
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder(toBuilder = true)
public class EndorserBlock {
    @Builder.Default
    private List<EndorserBlockTxRef> txRefs = new ArrayList<>();
    private String cbor;
    private String computedHash;

    public int txCount() {
        return txRefs != null ? txRefs.size() : 0;
    }

    public long totalTxBytes() {
        if (txRefs == null) {
            return 0;
        }
        return txRefs.stream().mapToLong(EndorserBlockTxRef::getTxSize).sum();
    }
}
