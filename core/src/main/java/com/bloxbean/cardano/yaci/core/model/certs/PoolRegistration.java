package com.bloxbean.cardano.yaci.core.model.certs;

import com.bloxbean.cardano.yaci.core.model.PoolParams;
import lombok.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode
@ToString
public class PoolRegistration implements Certificate{
    private final CertificateType type = CertificateType.POOL_REGISTRATION;

    private PoolParams poolParams;
}
