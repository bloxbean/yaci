package com.bloxbean.cardano.yaci.core.model.certs;

import lombok.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode
@ToString
public class GenesisKeyDelegation implements Certificate {
    private final CertificateType type = CertificateType.GENESIS_KEY_DELEGATION;

    private String genesisHash;
    private String genesisDelegateHash;
    private String vrfKeyHash;
}
