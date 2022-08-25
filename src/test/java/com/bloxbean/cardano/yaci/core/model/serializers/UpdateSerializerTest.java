package com.bloxbean.cardano.yaci.core.model.serializers;

import com.bloxbean.cardano.yaci.core.model.ProtocolParamUpdate;
import com.bloxbean.cardano.yaci.core.model.Update;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateSerializerTest {

    @Test
    void deserializeDI() {
        String cborHex = "82a7581cad5463153dc3d24b9ff133e46136028bdc1edbb897f5a7cf1b37950ca2021a0001400014821a00d59f801b00000002540be400581cb9547b8a57656539a8d9bc42c008e38d9c8bd9c8adbb1e73ad529497a2021a0001400014821a00d59f801b00000002540be400581cf7b341c14cd58fca4195a9b278cce1ef402dc0e06deb77e543cd1757a2021a0001400014821a00d59f801b00000002540be400581c162f94554ac8c225383a2248c245659eda870eaa82d0ef25fc7dcd82a2021a0001400014821a00d59f801b00000002540be400581c2075a095b3c844a29c24317a94a643ab8e22d54a3a3a72a420260af6a2021a0001400014821a00d59f801b00000002540be400581c268cfc0b89e910ead22e0ade91493d8212f53f3e2164b2e4bef0819ba2021a0001400014821a00d59f801b00000002540be400581c60baee25cbc90047e83fd01e1e57dc0b06d3d0cb150d0ab40bbfead1a2021a0001400014821a00d59f801b00000002540be40019013e";

        Update update = UpdateSerializer.INSTANCE.deserializeDI(CborSerializationUtil.deserialize(HexUtil.decodeHexString(cborHex)));

        assertThat(update.getEpoch()).isEqualTo(318);
        assertThat(update.getProtocolParamUpdates()).hasSize(7);
        ProtocolParamUpdate protocolParamUpdate = update.getProtocolParamUpdates().get("268cfc0b89e910ead22e0ade91493d8212f53f3e2164b2e4bef0819b");
        assertThat(protocolParamUpdate.getMaxBlockSize()).isEqualTo(81920);
        assertThat(protocolParamUpdate.getMaxTxExMem()).isEqualTo(BigInteger.valueOf(14000000));
        assertThat(protocolParamUpdate.getMaxTxExSteps()).isEqualTo(BigInteger.valueOf(10000000000L));
    }
}
