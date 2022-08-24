package com.bloxbean.cardano.yaci.core.model.certs;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@Getter
@EqualsAndHashCode
@ToString
public class StakeDelegation implements Certificate {
    private final CertificateType type = CertificateType.STAKE_DELEGATION;

    private final StakeCredential stakeCredential;
    private final StakePoolId stakePoolId;

    public StakeDelegation(StakeCredential stakeCredential, StakePoolId stakePoolId) {
        this.stakeCredential = stakeCredential;
        this.stakePoolId = stakePoolId;
    }
}
