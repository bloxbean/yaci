package com.bloxbean.cardano.yaci.node.app.api.accounts.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class AccountStateDtos {

    public record StakeRegistrationDto(
            @JsonProperty("credential") String credential,
            @JsonProperty("credential_type") String credentialType,
            @JsonProperty("reward_balance") String rewardBalance,
            @JsonProperty("deposit") String deposit
    ) {}

    public record PoolDelegationDto(
            @JsonProperty("credential") String credential,
            @JsonProperty("credential_type") String credentialType,
            @JsonProperty("pool_hash") String poolHash,
            @JsonProperty("slot") long slot,
            @JsonProperty("tx_index") int txIndex,
            @JsonProperty("cert_index") int certIndex
    ) {}

    public record DRepDelegationDto(
            @JsonProperty("credential") String credential,
            @JsonProperty("credential_type") String credentialType,
            @JsonProperty("drep_type") String drepType,
            @JsonProperty("drep_hash") String drepHash,
            @JsonProperty("slot") long slot,
            @JsonProperty("tx_index") int txIndex,
            @JsonProperty("cert_index") int certIndex
    ) {}

    public record PoolDto(
            @JsonProperty("pool_hash") String poolHash,
            @JsonProperty("deposit") String deposit
    ) {}

    public record PoolRetirementDto(
            @JsonProperty("pool_hash") String poolHash,
            @JsonProperty("retirement_epoch") long retirementEpoch
    ) {}

    private AccountStateDtos() {}
}
