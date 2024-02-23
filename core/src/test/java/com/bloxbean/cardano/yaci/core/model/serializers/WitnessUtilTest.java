package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.yaci.core.model.Redeemer;
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
}
