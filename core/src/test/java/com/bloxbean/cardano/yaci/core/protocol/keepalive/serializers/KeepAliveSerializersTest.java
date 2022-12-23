package com.bloxbean.cardano.yaci.core.protocol.keepalive.serializers;

import com.bloxbean.cardano.yaci.core.protocol.keepalive.messages.MsgKeepAlive;
import com.bloxbean.cardano.yaci.core.protocol.keepalive.messages.MsgKeepAliveResponse;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KeepAliveSerializersTest {
    @Test
    void keepAliveSerializeTest() {
        MsgKeepAlive msgKeepAlive = new MsgKeepAlive(12345);
        String cbor = HexUtil.encodeHexString(KeepAliveSerializers.MsgKeepAliveSerializer.INSTANCE.serialize(msgKeepAlive));
        assertThat(cbor).isEqualTo("8200193039");
    }

    @Test
    void keepAliveResponseSerializeTest() {
        MsgKeepAliveResponse msgKeepAliveResponse = new MsgKeepAliveResponse(12345);
        String cbor = HexUtil.encodeHexString(KeepAliveSerializers.MsgKeepAliveResponseSerializer.INSTANCE.serialize(msgKeepAliveResponse));
        assertThat(cbor).isEqualTo("8201193039");
    }
}
