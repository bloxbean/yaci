package com.bloxbean.cardano.yaci.core.model.serializers;

import com.bloxbean.cardano.yaci.core.model.certs.PoolRetirement;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PoolRetirementSerializerTest {

    @Test
    void deserializeDI() {
        String cborHex = "8304581cae8dbaaa4ebfdba74618653a619d28d58232638ac83ccb5d66edee36190164";

        PoolRetirement certificate = PoolRetirementSerializer.INSTANCE.deserializeDI(CborSerializationUtil.deserializeOne(HexUtil.decodeHexString(cborHex)));

        assertThat(certificate.getPoolKeyHash()).isEqualTo("ae8dbaaa4ebfdba74618653a619d28d58232638ac83ccb5d66edee36");
        assertThat(certificate.getEpoch()).isEqualTo(356);
    }
}
