package com.bloxbean.cardano.yaci.core.model.certs;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = GenesisKeyDelegation.class, name = "GENESIS_KEY_DELEGATION"),
        @JsonSubTypes.Type(value = MoveInstataneous.class, name = "MOVE_INSTATENEOUS_REWARDS_CERT"),
        @JsonSubTypes.Type(value = PoolRegistration.class, name = "POOL_REGISTRATION"),
        @JsonSubTypes.Type(value = PoolRetirement.class, name = "POOL_RETIREMENT"),
        @JsonSubTypes.Type(value = StakeDelegation.class, name = "STAKE_DELEGATION"),
        @JsonSubTypes.Type(value = StakeDeregistration.class, name = "STAKE_DEREGISTRATION"),
        @JsonSubTypes.Type(value = StakeRegistration.class, name = "STAKE_REGISTRATION")
})
public interface Certificate {
    CertificateType getType();
}
