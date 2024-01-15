package com.bloxbean.cardano.yaci.core.model.certs;

import com.bloxbean.cardano.yaci.core.model.Credential;
import lombok.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode
@ToString
public class AuthCommitteeHotCert implements Certificate {
    private final CertificateType type = CertificateType.AUTH_COMMITTEE_HOT_CERT;

    private Credential committeeColdCredential;
    private Credential committeeHotCredential;
}
