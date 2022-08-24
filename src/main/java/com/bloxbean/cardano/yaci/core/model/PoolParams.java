package com.bloxbean.cardano.yaci.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class PoolParams {
    private String operator;
    private String vrfKeyHash;
    private BigInteger pledge;
    private BigInteger cost;
    private String margin;
    private String rewardAccount;
    private Set<String> poolOwners;
    private List<Relay> relays;

    //pool_metadata
    private String poolMetadataUrl;
    private String poolMetadataHash;
}
