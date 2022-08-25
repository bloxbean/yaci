package com.bloxbean.cardano.yaci.core.model.serializers;

import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WithdrawalsSerializerTest {

    @Test
    void deserializeDI() {
        String cbor ="a1581de15ffc1b03f3322282077f1d3e55200b44f111be643aa150819f87fb521a004c4048";

        Map<String, BigInteger> withdrawals = WithdrawalsSerializer.INSTANCE.deserializeDI(CborSerializationUtil.deserialize(HexUtil.decodeHexString(cbor)));

        assertThat(withdrawals).hasSize(1);
        assertThat(withdrawals.keySet()).contains("e15ffc1b03f3322282077f1d3e55200b44f111be643aa150819f87fb52");
    }
}
