package com.bloxbean.cardano.yaci.core.model;

import com.bloxbean.cardano.yaci.core.types.UnitInterval;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
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

    /**
     * The BLS key of a Dijkstra pool registration, or null if it has none.
     * <p>
     * Experimental: Dijkstra is not final; this may change in any release.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BlsKey blsKey;

    /** Kept for source and binary compatibility: the BLS key is null. Use the builder for it. */
    public PoolParams(String operator, String vrfKeyHash, BigInteger pledge, BigInteger cost, UnitInterval margin,
                      String rewardAccount, Set<String> poolOwners, List<Relay> relays, String poolMetadataUrl,
                      String poolMetadataHash) {
        this(operator, vrfKeyHash, pledge, cost, margin, rewardAccount, poolOwners, relays, poolMetadataUrl,
                poolMetadataHash, null);
    }
}
