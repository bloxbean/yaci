package com.bloxbean.cardano.yaci.core.model.certs;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@Getter
@EqualsAndHashCode
@ToString
public class StakeDeregistration implements Certificate {
    private final CertificateType type = CertificateType.STAKE_DEREGISTRATION;

    private final StakeCredential stakeCredential;

    public StakeDeregistration(StakeCredential stakeCredential) {
        this.stakeCredential = stakeCredential;
    }
}
