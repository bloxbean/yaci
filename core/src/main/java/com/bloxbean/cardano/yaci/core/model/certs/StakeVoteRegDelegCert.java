package com.bloxbean.cardano.yaci.core.model.certs;

import com.bloxbean.cardano.yaci.core.model.governance.Drep;
import lombok.*;

import java.math.BigInteger;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode
@ToString
public class StakeVoteRegDelegCert implements Certificate {
    private final CertificateType type = CertificateType.STAKE_VOTE_REG_DELEG_CERT;

    private StakeCredential stakeCredential;
    private String poolKeyHash;
    private Drep drep;
    private BigInteger coin;
}
