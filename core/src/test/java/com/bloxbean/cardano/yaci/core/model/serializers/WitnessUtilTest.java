package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.yaci.core.model.ExUnits;
import com.bloxbean.cardano.yaci.core.model.Redeemer;
import com.bloxbean.cardano.yaci.core.model.RedeemerTag;
import com.bloxbean.cardano.yaci.core.model.serializers.util.WitnessUtil;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.core.util.Tuple;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class WitnessUtilTest {

    @Test
    void getRedeemerMapBytes() throws Exception {

        var redeemerTuple1 = createRedeemerKeyValue(2, 1, new BigIntPlutusData(BigInteger.valueOf(777000)),
                BigInteger.valueOf(22000), BigInteger.valueOf(33000));
        var redeemerTuple2 = createRedeemerKeyValue(3, 0, new BigIntPlutusData(BigInteger.valueOf(1000)),
                BigInteger.valueOf(2000), BigInteger.valueOf(3000));

        co.nstant.in.cbor.model.Map redeemerMap = new co.nstant.in.cbor.model.Map();
        redeemerMap.put(redeemerTuple1._1, redeemerTuple1._2);
        redeemerMap.put(redeemerTuple2._1, redeemerTuple2._2);

        var redeemerBytes = CborSerializationUtil.serialize(redeemerMap);
        System.out.println("Redeemer bytes: " + HexUtil.encodeHexString(redeemerBytes));

        List<Tuple<byte[], byte[]>> deRedeemerList = WitnessUtil.getRedeemerMapBytes(redeemerBytes);

        List<Redeemer> redeemers = new ArrayList<>();
        for (var redeemerTuple : deRedeemerList) {
            var keyArrayDI = (Array) CborSerializationUtil.deserializeOne(redeemerTuple._1);
            var valueArrayDI = (Array) CborSerializationUtil.deserializeOne(redeemerTuple._2);

            Redeemer redeemer = Redeemer.deserialize(keyArrayDI, valueArrayDI);
            redeemers.add(redeemer);
        }

        assertThat(redeemers.size()).isEqualTo(2);
        assertThat(redeemers.get(0).getTag()).isEqualTo(RedeemerTag.Cert);
        assertThat(redeemers.get(0).getIndex()).isEqualTo(1);
        assertThat(redeemers.get(0).getData().getCbor()).isEqualTo(BigIntPlutusData.of(777000).serializeToHex());
        assertThat(redeemers.get(0).getExUnits().getMem()).isEqualTo(BigInteger.valueOf(22000));
        assertThat(redeemers.get(0).getExUnits().getSteps()).isEqualTo(BigInteger.valueOf(33000));

        assertThat(redeemers.get(1).getTag()).isEqualTo(RedeemerTag.Reward);
        assertThat(redeemers.get(1).getIndex()).isEqualTo(0);
        assertThat(redeemers.get(1).getData().getCbor()).isEqualTo(BigIntPlutusData.of(1000).serializeToHex());
        assertThat(redeemers.get(1).getExUnits().getMem()).isEqualTo(BigInteger.valueOf(2000));
        assertThat(redeemers.get(1).getExUnits().getSteps()).isEqualTo(BigInteger.valueOf(3000));

    }

    @Test
    @SneakyThrows
    void getArrayBytes() {
        var redeemer1 = createRedeemerArray(2, 1, new BigIntPlutusData(BigInteger.valueOf(777000)),
                BigInteger.valueOf(22000), BigInteger.valueOf(33000));
        var redeemer2 = createRedeemerArray(3, 0, new BigIntPlutusData(BigInteger.valueOf(1000)),
                BigInteger.valueOf(2000), BigInteger.valueOf(3000));

        Array redeemerArray = new Array();
        redeemerArray.add(redeemer1);
        redeemerArray.add(redeemer2);

        var redeemerBytes = CborSerializationUtil.serialize(redeemerArray);

        var deRedeemerBytesList = WitnessUtil.getArrayBytes(redeemerBytes);

        List<Redeemer> redeemers = new ArrayList<>();
        for (byte[] redeemerByte : deRedeemerBytesList) {
            var deRedeemerArray = (Array) CborSerializationUtil.deserializeOne(redeemerByte);
            Redeemer redeemer = Redeemer.deserializePreConway(deRedeemerArray);
            redeemers.add(redeemer);
        }

        assertThat(redeemers.size()).isEqualTo(2);
        assertThat(redeemers.get(0).getTag()).isEqualTo(RedeemerTag.Cert);
        assertThat(redeemers.get(0).getIndex()).isEqualTo(1);
        assertThat(redeemers.get(0).getData().getCbor()).isEqualTo(BigIntPlutusData.of(777000).serializeToHex());
        assertThat(redeemers.get(0).getExUnits().getMem()).isEqualTo(BigInteger.valueOf(22000));
        assertThat(redeemers.get(0).getExUnits().getSteps()).isEqualTo(BigInteger.valueOf(33000));

        assertThat(redeemers.get(1).getTag()).isEqualTo(RedeemerTag.Reward);
        assertThat(redeemers.get(1).getIndex()).isEqualTo(0);
        assertThat(redeemers.get(1).getData().getCbor()).isEqualTo(BigIntPlutusData.of(1000).serializeToHex());
        assertThat(redeemers.get(1).getExUnits().getMem()).isEqualTo(BigInteger.valueOf(2000));
        assertThat(redeemers.get(1).getExUnits().getSteps()).isEqualTo(BigInteger.valueOf(3000));
    }

    @SneakyThrows
    public static Tuple<Array, Array> createRedeemerKeyValue(int tag, int index, BigIntPlutusData data,
                                               BigInteger mem, BigInteger steps) {
        var redeemerTag = new UnsignedInteger(tag);
        var redeemerIndex = new UnsignedInteger(index);

        var redeemerData = data.serialize();

        ExUnits exUnits = ExUnits.builder()
                .mem(mem)
                .steps(steps)
                .build();

        var exUnitsDI = exUnits.serialize();

        var keyArray = new Array();
        keyArray.add(redeemerTag);
        keyArray.add(redeemerIndex);

        var valueArray = new Array();
        valueArray.add(redeemerData);
        valueArray.add(exUnitsDI);

        return new Tuple<>(keyArray, valueArray);
    }

    @SneakyThrows
    public static Array createRedeemerArray(int tag, int index, BigIntPlutusData data,
                                               BigInteger mem, BigInteger steps) {
        var redeemerTag = new UnsignedInteger(tag);
        var redeemerIndex = new UnsignedInteger(index);

        var redeemerData = data.serialize();

        ExUnits exUnits = ExUnits.builder()
                .mem(mem)
                .steps(steps)
                .build();

        var exUnitsDI = exUnits.serialize();

        Array array = new Array();
        array.add(redeemerTag);
        array.add(redeemerIndex);
        array.add(redeemerData);
        array.add(exUnitsDI);

        return array;
    }

    @Test
    @SneakyThrows
    public void parseDatumArrayBytesWithTag() { //Conway
        String datumArrayHex = "d901028cd8799f581c57abe9a8de8730b0bbf1962b3e0858019a3f013218eeceac46d43220d8799fd8799f581c57abe9a8de8730b0bbf1962b3e0858019a3f013218eeceac46d43220ffd8799fd8799fd8799f581cadd78874051724b4f838828e579e5ddca7d1d38bf58963890808ba7affffffffd8799f4040ff1a01c9c3801a01c9c380d8799f581c171163f05e4f30b6be3c22668c37978e7d508b84f83558e523133cdf4474454d50ffd8799f1b01400000000000001b00596c9e236c15d5ff58201ea5abe908b30d2e51a7bb0174c8f3a35f925109ca3ca77e84f980233c151dc3d87a80d87a80001a000f42401a000f4240d8799f1a000f42401a00015f9000ff00ffd8799fd8799f5820e86ef9e7cb098acc7b31df42c045320e1f4d171ef0bfdc02dc5384739cc86b66ff00ffd8799f581c57abe9a8de8730b0bbf1962b3e0858019a3f013218eeceac46d43220d8799fd8799f581c57abe9a8de8730b0bbf1962b3e0858019a3f013218eeceac46d43220ffd8799fd8799fd8799f581cadd78874051724b4f838828e579e5ddca7d1d38bf58963890808ba7affffffffd8799f581c171163f05e4f30b6be3c22668c37978e7d508b84f83558e523133cdf4474454d50ff1a01c9c3801a01c9c380d8799f4040ffd8799f1b00e4875b3e1437cb1b0280000000000000ff5820208b42a46ed8cd266834fda53f1a27c2f7598dbe9ee898ba423527fcabddc42dd87a80d87a80001a000f42401a000f4240d8799f1a000f42401a00015f9000ff00ffd8799f581c57abe9a8de8730b0bbf1962b3e0858019a3f013218eeceac46d43220d8799fd8799f581c57abe9a8de8730b0bbf1962b3e0858019a3f013218eeceac46d43220ffd8799fd8799fd8799f581cadd78874051724b4f838828e579e5ddca7d1d38bf58963890808ba7affffffffd8799f4040ff1a01c9c3801a01c9c380d8799f581c171163f05e4f30b6be3c22668c37978e7d508b84f83558e523133cdf4474454d50ffd8799f1b00280000000000001b0009ef9fcb0c026dff5820757e1e9d8c807ae14c7374619a8f55b025cfaf0059befe46d7b0afb8c281ff5fd87a80d87a80001a000f42401a000f4240d8799f1a000f42401a00015f9000ff00ffd8799f581c57abe9a8de8730b0bbf1962b3e0858019a3f013218eeceac46d43220d8799fd8799f581c57abe9a8de8730b0bbf1962b3e0858019a3f013218eeceac46d43220ffd8799fd8799fd8799f581cadd78874051724b4f838828e579e5ddca7d1d38bf58963890808ba7affffffffd8799f4040ff1a01c9c3801a01c9c380d8799f581c171163f05e4f30b6be3c22668c37978e7d508b84f83558e523133cdf4474454d50ffd8799f1b02800000000000001b00a8e99c7bcc293dff5820800c3d9eb1b7ce2a85215b94e8beeedf5e11bf440703f2f9203ef551c13426d4d87a80d87a80001a000f42401a000f4240d8799f1a000f42401a00015f9000ff00ffd8799fd8799f58201038cf4c79b33a52c12febae9611be6a7b29e979864f191c3b0f94b96be8ab44ff00ffd8799f581c57abe9a8de8730b0bbf1962b3e0858019a3f013218eeceac46d43220d8799fd8799f581c57abe9a8de8730b0bbf1962b3e0858019a3f013218eeceac46d43220ffd8799fd8799fd8799f581cadd78874051724b4f838828e579e5ddca7d1d38bf58963890808ba7affffffffd8799f581c171163f05e4f30b6be3c22668c37978e7d508b84f83558e523133cdf4474454d50ff1a01c9c3801a01c9c380d8799f4040ffd8799f1b001dcedf612407471b0050000000000000ff58207adcc7a92f6d1202346bf5b1e753f34421b8036f7b8379e4a0803df105c6ebbdd87a80d87a80001a000f42401a000f4240d8799f1a000f42401a00015f9000ff00ffd8799fd8799f5820f9d5aebf7fc08f7e92e2d75654acffbf8aac7fb45230b3fae3146980440e5edeff00ffd8799f581c57abe9a8de8730b0bbf1962b3e0858019a3f013218eeceac46d43220d8799fd8799f581c57abe9a8de8730b0bbf1962b3e0858019a3f013218eeceac46d43220ffd8799fd8799fd8799f581cadd78874051724b4f838828e579e5ddca7d1d38bf58963890808ba7affffffffd8799f581c171163f05e4f30b6be3c22668c37978e7d508b84f83558e523133cdf4474454d50ff1a01c9c3801a01c9c380d8799f4040ffd8799f1b006d4bddb9841aaf1b0140000000000000ff582009c178687b538a7df7f0934a86aebe386930bd692f6b99662e986b89b82860d2d87a80d87a80001a000f42401a000f4240d8799f1a000f42401a00015f9000ff00ffd8799fd8799f5820d9ed5f4d544d8da842fc2952ee3bd5a84fb45036f83decc7664ac4422ae678f2ff00ffd8799fd8799f582093ed44a05680f8c6978c7dea392c95c78c8f9d909e657c8a7c589566b6d7e8d2ff00ffd8799fd8799f5820eb2442a4862716a687ad2054c5f25595abe1a9be03da0f8ff9264576bb6e24dcff00ff";

        var datums = WitnessUtil.getArrayBytes(HexUtil.decodeHexString(datumArrayHex));

        assertThat(datums.size()).isEqualTo(12);
    }
}
