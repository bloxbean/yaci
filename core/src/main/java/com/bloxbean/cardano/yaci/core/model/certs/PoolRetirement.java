package com.bloxbean.cardano.yaci.core.model.certs;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@Getter
@AllArgsConstructor
@EqualsAndHashCode
@ToString
public class PoolRetirement implements Certificate {
    private final CertificateType type = CertificateType.POOL_RETIREMENT;

    private String poolKeyHash;
    private long epoch;
}
