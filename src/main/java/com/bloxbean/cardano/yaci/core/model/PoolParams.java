package com.bloxbean.cardano.yaci.core.model;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;

public class PoolParams {
    private String operator;
    private String vrfKeyHash;
    private BigInteger pledge;
    private BigInteger cost;
    private String unitInterval; //TODO
    private String rewardAccount; //TODO
    private Set<String> addrKeyHash;
    private List<Relay> relays;
//    private String poolMetadata; //TODO
}
