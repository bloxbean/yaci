package com.bloxbean.cardano.yaci.core.model.certs;

import com.bloxbean.cardano.yaci.core.model.Credential;
import com.bloxbean.cardano.yaci.core.model.governance.Anchor;
import lombok.*;

import java.math.BigInteger;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode
@ToString
public class RegDrepCert implements Certificate {
    private final CertificateType type = CertificateType.REG_DREP_CERT;

    private Credential drepCredential;
    private BigInteger coin;
    private Anchor anchor;
}
