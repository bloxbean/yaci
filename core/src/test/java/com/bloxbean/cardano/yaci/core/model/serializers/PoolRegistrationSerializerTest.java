package com.bloxbean.cardano.yaci.core.model.serializers;

import com.bloxbean.cardano.yaci.core.model.Relay;
import com.bloxbean.cardano.yaci.core.model.certs.PoolRegistration;
import com.bloxbean.cardano.yaci.core.types.UnitInterval;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

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
        assertThat(poolRegistration.getPoolParams().getMargin()).isEqualTo(new UnitInterval(BigInteger.valueOf(1), BigInteger.valueOf(100)));
        assertThat(poolRegistration.getPoolParams().getRewardAccount()).isEqualTo("e1ab737aaf2b421c22a15de788d61d375b135da0cbf1850fbd285d5a74");
        assertThat(poolRegistration.getPoolParams().getPoolOwners()).contains("ab737aaf2b421c22a15de788d61d375b135da0cbf1850fbd285d5a74");
        assertThat(poolRegistration.getPoolParams().getPoolMetadataUrl()).isEqualTo("https://bit.ly/3AfwvRQ");
        assertThat(poolRegistration.getPoolParams().getPoolMetadataHash()).isEqualTo("e413cf3890f9a323c4b0e2cbdf06c6f66a2b0ff1f84b1a2346178e9b54dfff0c");
        assertThat(poolRegistration.getPoolParams().getRelays()).contains(new Relay(6000, "47.91.21.36", null, null), new Relay(6000, "47.74.42.94", null, null));
    }

    @Test
    void dijkstraRegistrationWithBlsKeyDecodesTheSamePoolParams() {
        //Conway certificate from deserializeDI(): 8a 03 <operator: 581c + 28 bytes> <vrf: 5820 + 32 bytes> <pledge> ...
        String conway = "8a03581ce319e1a85cf976abef526d34d223392b5a3f4a799d090492828999915820ca6306b471f5937ac69bda9b04915ff71a450644f36ea6163403dc2666659b8b1a05f5e1001a1443fd00d81e82011864581de1ab737aaf2b421c22a15de788d61d375b135da0cbf1850fbd285d5a7481581cab737aaf2b421c22a15de788d61d375b135da0cbf1850fbd285d5a74828400191770442f5b1524f68400191770442f4a2a5ef6827668747470733a2f2f6269742e6c792f334166777652515820e413cf3890f9a323c4b0e2cbdf06c6f66a2b0ff1f84b1a2346178e9b54dfff0c";
        int afterVrf = 4 + 2 * (2 + 28) + 2 * (2 + 32);
        //Dijkstra bls_key = [bls_pubkey (96 bytes), proof_of_possession (48 bytes)], or nil
        String blsKey = "82" + "5860" + "11".repeat(96) + "5830" + "22".repeat(48);
        PoolRegistration expected = decode(conway);

        for (String inserted : new String[]{blsKey, "f6"}) {
            String dijkstra = "8b" + conway.substring(2, afterVrf) + inserted + conway.substring(afterVrf);

            PoolRegistration parsed = decode(dijkstra);

            assertThat(parsed.getPoolParams()).usingRecursiveComparison()
                    .ignoringFields("blsPublicKey", "blsPossessionProof")
                    .isEqualTo(expected.getPoolParams());
            boolean nil = inserted.equals("f6");
            assertThat(parsed.getPoolParams().getBlsPublicKey()).isEqualTo(nil ? null : "11".repeat(96));
            assertThat(parsed.getPoolParams().getBlsPossessionProof()).isEqualTo(nil ? null : "22".repeat(48));
        }
        assertThat(expected.getPoolParams().getBlsPublicKey()).isNull();
    }

    private static PoolRegistration decode(String hex) {
        return PoolRegistrationSerializer.INSTANCE.deserializeDI(CborSerializationUtil.deserializeOne(HexUtil.decodeHexString(hex)));
    }
}
