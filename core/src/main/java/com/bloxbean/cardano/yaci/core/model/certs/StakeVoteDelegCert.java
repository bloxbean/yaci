package com.bloxbean.cardano.yaci.core.model.certs;

import com.bloxbean.cardano.yaci.core.model.governance.Drep;
import lombok.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode
@ToString
public class StakeVoteDelegCert implements Certificate {
    private final CertificateType type = CertificateType.STAKE_VOTE_DELEG_CERT;

    private StakeCredential stakeCredential;
    private String poolKeyHash;
    private Drep drep;
}
