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

    //Dijkstra era bls_key = [bls_pubkey, bls_possession_proof] (hex); null when absent or nil
    private String blsPublicKey;
    private String blsPossessionProof;

    /**
     * Pre-Dijkstra constructor, kept so existing callers compile and behave unchanged.
     */
    public PoolParams(String operator, String vrfKeyHash, BigInteger pledge, BigInteger cost, UnitInterval margin,
                      String rewardAccount, Set<String> poolOwners, List<Relay> relays, String poolMetadataUrl,
                      String poolMetadataHash) {
        this(operator, vrfKeyHash, pledge, cost, margin, rewardAccount, poolOwners, relays, poolMetadataUrl,
                poolMetadataHash, null, null);
    }
}
