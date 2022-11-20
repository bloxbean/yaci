package com.bloxbean.cardano.yaci.core.model.certs;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@Getter
@EqualsAndHashCode
@ToString
public class StakeRegistration implements Certificate {
    private final CertificateType type = CertificateType.STAKE_REGISTRATION;

    private final StakeCredential stakeCredential;

    public StakeRegistration(StakeCredential stakeCredential) {
        this.stakeCredential = stakeCredential;
    }
}
