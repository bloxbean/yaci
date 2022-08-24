package com.bloxbean.cardano.yaci.core.model.certs;

import com.bloxbean.cardano.yaci.core.model.PoolParams;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class PoolRegistration implements Certificate{
    private final CertificateType type = CertificateType.POOL_REGISTRATION;

    private PoolParams poolParams;
}
