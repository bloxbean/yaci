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
        @JsonSubTypes.Type(value = StakeRegistration.class, name = "STAKE_REGISTRATION"),
        @JsonSubTypes.Type(value = RegCert.class, name = "REG_CERT"),
        @JsonSubTypes.Type(value = UnregCert.class, name = "UNREG_CERT"),
        @JsonSubTypes.Type(value = VoteDelegCert.class, name = "VOTE_DELEG_CERT"),
        @JsonSubTypes.Type(value = StakeVoteDelegCert.class, name = "STAKE_VOTE_DELEG_CERT"),
        @JsonSubTypes.Type(value = StakeRegDelegCert.class, name = "STAKE_REG_DELEG_CERT"),
        @JsonSubTypes.Type(value = VoteRegDelegCert.class, name = "VOTE_REG_DELEG_CERT"),
        @JsonSubTypes.Type(value = StakeVoteRegDelegCert.class, name = "STAKE_VOTE_REG_DELEG_CERT"),
        @JsonSubTypes.Type(value = AuthCommitteeHotCert.class, name = "AUTH_COMMITTEE_HOT_CERT"),
        @JsonSubTypes.Type(value = ResignCommitteeColdCert.class, name = "RESIGN_COMMITTEE_COLD_CERT"),
        @JsonSubTypes.Type(value = RegDrepCert.class, name = "REG_DREP_CERT"),
        @JsonSubTypes.Type(value = UnregDrepCert.class, name = "UNREG_DREP_CERT"),
        @JsonSubTypes.Type(value = UpdateDrepCert.class, name = "UPDATE_DREP_CERT")

})
public interface Certificate {
    CertificateType getType();
}
