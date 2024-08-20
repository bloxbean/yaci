package com.bloxbean.cardano.yaci.core.protocol.localstate.queries.model;

import com.bloxbean.cardano.yaci.core.model.governance.Anchor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigInteger;

@Getter
@Setter
@Builder
public class DRepState {
    private String dRepHash;
    private Anchor anchor;
    private BigInteger deposit;
    private Integer expiry;
}
