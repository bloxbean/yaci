package com.bloxbean.cardano.yaci.core.model.certs;

import com.bloxbean.cardano.yaci.core.model.Credential;
import lombok.*;

import java.math.BigInteger;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode
@ToString
public class UnregDrepCert implements Certificate {
    private final CertificateType type = CertificateType.UNREG_DREP_CERT;

    private Credential drepCredential;
    private BigInteger coin;
}
