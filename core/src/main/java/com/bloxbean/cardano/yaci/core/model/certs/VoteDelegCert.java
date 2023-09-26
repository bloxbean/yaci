package com.bloxbean.cardano.yaci.core.model.certs;

import com.bloxbean.cardano.yaci.core.model.governance.Drep;
import lombok.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode
@ToString
public class VoteDelegCert implements Certificate {
    private final CertificateType type = CertificateType.VOTE_DELEG_CERT;

    private StakeCredential stakeCredential;
    private Drep drep;
}
