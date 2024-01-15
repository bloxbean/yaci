package com.bloxbean.cardano.yaci.core.model.certs;

import com.bloxbean.cardano.yaci.core.model.Credential;
import com.bloxbean.cardano.yaci.core.model.governance.Anchor;
import lombok.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode
@ToString
public class UpdateDrepCert implements Certificate {
    private final CertificateType type = CertificateType.UPDATE_DREP_CERT;

    private Credential drepCredential;
    private Anchor anchor;
}
