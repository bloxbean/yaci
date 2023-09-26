package com.bloxbean.cardano.yaci.core.model.certs;

import lombok.*;

import java.math.BigInteger;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode
@ToString
public class StakeRegDelegCert implements Certificate {
    private final CertificateType type = CertificateType.STAKE_REG_DELEG_CERT;

    private StakeCredential stakeCredential;
    private String poolKeyHash;
    private BigInteger coin;
}
