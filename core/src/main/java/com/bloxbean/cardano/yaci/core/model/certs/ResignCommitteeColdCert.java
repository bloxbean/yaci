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
public class ResignCommitteeColdCert implements Certificate {
    private final CertificateType type = CertificateType.RESIGN_COMMITTEE_COLD_CERT;

    private Credential committeeColdCredential;
    private Anchor anchor;
}
