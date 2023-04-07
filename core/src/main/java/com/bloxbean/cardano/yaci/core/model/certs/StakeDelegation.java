package com.bloxbean.cardano.yaci.core.model.certs;

import lombok.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode
@ToString
public class StakeDelegation implements Certificate {
    private final CertificateType type = CertificateType.STAKE_DELEGATION;

    private StakeCredential stakeCredential;
    private StakePoolId stakePoolId;
}
