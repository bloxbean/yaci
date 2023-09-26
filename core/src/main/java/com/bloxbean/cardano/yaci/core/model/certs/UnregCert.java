package com.bloxbean.cardano.yaci.core.model.certs;

import lombok.*;

import java.math.BigInteger;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode
@ToString
public class UnregCert implements Certificate {
    private final CertificateType type = CertificateType.UNREG_CERT;

    private StakeCredential stakeCredential;
    private BigInteger coin;
}
