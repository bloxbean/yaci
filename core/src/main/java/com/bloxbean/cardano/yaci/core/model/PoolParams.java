package com.bloxbean.cardano.yaci.core.model;

import com.bloxbean.cardano.yaci.core.types.UnitInterval;
import lombok.*;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@ToString
public class PoolParams {
    private String operator;
    private String vrfKeyHash;
    private BigInteger pledge;
    private BigInteger cost;
    private UnitInterval margin;
    private String rewardAccount;
    private Set<String> poolOwners;
    private List<Relay> relays;

    //pool_metadata
    private String poolMetadataUrl;
    private String poolMetadataHash;
}
