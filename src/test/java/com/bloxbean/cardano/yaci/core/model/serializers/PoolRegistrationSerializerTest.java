package com.bloxbean.cardano.yaci.core.model.serializers;

import com.bloxbean.cardano.yaci.core.model.Relay;
import com.bloxbean.cardano.yaci.core.model.certs.PoolRegistration;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PoolRegistrationSerializerTest {

    @Test
    void deserializeDI() {
        String cborHex = "8a03581ce319e1a85cf976abef526d34d223392b5a3f4a799d090492828999915820ca6306b471f5937ac69bda9b04915ff71a450644f36ea6163403dc2666659b8b1a05f5e1001a1443fd00d81e82011864581de1ab737aaf2b421c22a15de788d61d375b135da0cbf1850fbd285d5a7481581cab737aaf2b421c22a15de788d61d375b135da0cbf1850fbd285d5a74828400191770442f5b1524f68400191770442f4a2a5ef6827668747470733a2f2f6269742e6c792f334166777652515820e413cf3890f9a323c4b0e2cbdf06c6f66a2b0ff1f84b1a2346178e9b54dfff0c";

        PoolRegistration poolRegistration = PoolRegistrationSerializer.INSTANCE.deserializeDI(CborSerializationUtil.deserializeOne(HexUtil.decodeHexString(cborHex)));

        assertThat(poolRegistration.getPoolParams().getOperator()).isEqualTo("e319e1a85cf976abef526d34d223392b5a3f4a799d09049282899991");
        assertThat(poolRegistration.getPoolParams().getVrfKeyHash()).isEqualTo("ca6306b471f5937ac69bda9b04915ff71a450644f36ea6163403dc2666659b8b");
        assertThat(poolRegistration.getPoolParams().getPledge()).isEqualTo(100000000);
        assertThat(poolRegistration.getPoolParams().getCost()).isEqualTo(340000000);
        assertThat(poolRegistration.getPoolParams().getMargin()).isEqualTo("1/100");
        assertThat(poolRegistration.getPoolParams().getRewardAccount()).isEqualTo("e1ab737aaf2b421c22a15de788d61d375b135da0cbf1850fbd285d5a74");
        assertThat(poolRegistration.getPoolParams().getPoolOwners()).contains("ab737aaf2b421c22a15de788d61d375b135da0cbf1850fbd285d5a74");
        assertThat(poolRegistration.getPoolParams().getPoolMetadataUrl()).isEqualTo("https://bit.ly/3AfwvRQ");
        assertThat(poolRegistration.getPoolParams().getPoolMetadataHash()).isEqualTo("e413cf3890f9a323c4b0e2cbdf06c6f66a2b0ff1f84b1a2346178e9b54dfff0c");
        assertThat(poolRegistration.getPoolParams().getRelays()).contains(new Relay(6000, "47.91.21.36", null, null), new Relay(6000, "47.74.42.94", null, null));
    }
}
