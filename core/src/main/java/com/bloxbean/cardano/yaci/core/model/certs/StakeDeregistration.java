package com.bloxbean.cardano.yaci.core.model.certs;

import lombok.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode
@ToString
public class StakeDeregistration implements Certificate {
    private final CertificateType type = CertificateType.STAKE_DEREGISTRATION;

    private StakeCredential stakeCredential;
}
