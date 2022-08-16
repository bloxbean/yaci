package com.bloxbean.cardano.yaci.core.model.certs;

import com.bloxbean.cardano.yaci.core.model.PoolParams;

public class PoolRegistration implements Certificate{
    private final CertificateType type = CertificateType.POOL_REGISTRATION;

    private PoolParams poolParams;
}
