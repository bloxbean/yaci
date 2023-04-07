package com.bloxbean.cardano.yaci.core.model.certs;

import lombok.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode
@ToString
public class PoolRetirement implements Certificate {
    private final CertificateType type = CertificateType.POOL_RETIREMENT;

    private String poolKeyHash;
    private long epoch;
}
