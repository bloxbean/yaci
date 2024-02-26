package com.bloxbean.cardano.yaci.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum RedeemerTag {
    @JsonProperty("spend")
    Spend(0),
    @JsonProperty("mint")
    Mint(1),
    @JsonProperty("cert")
    Cert(2),
    @JsonProperty("reward")
    Reward(3),
    @JsonProperty("voting")
    Voting(4),
    @JsonProperty("proposing")
    Proposing(5);

    public final int value;

    RedeemerTag(int value) {
        this.value = value;
    }
}

