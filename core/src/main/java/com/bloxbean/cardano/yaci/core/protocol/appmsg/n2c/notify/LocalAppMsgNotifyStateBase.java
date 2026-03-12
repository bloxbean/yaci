package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2c.notify;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.bloxbean.cardano.yaci.core.protocol.appmsg.n2c.notify.serializers.LocalAppMsgNotifySerializers.*;

public interface LocalAppMsgNotifyStateBase extends State {
    Logger log = LoggerFactory.getLogger(LocalAppMsgNotifyStateBase.class);

    default Message handleInbound(byte[] bytes) {
        try {
            DataItem di = CborSerializationUtil.deserializeOne(bytes);
            Array array = (Array) di;
            int id = ((UnsignedInteger) array.getDataItems().get(0)).getValue().intValue();
            switch (id) {
                case 0:
                    return MsgRequestMessagesSerializer.INSTANCE.deserialize(bytes);
                case 1:
                    return MsgReplyMessagesNonBlockingSerializer.INSTANCE.deserialize(bytes);
                case 2:
                    return MsgReplyMessagesBlockingSerializer.INSTANCE.deserialize(bytes);
                case 3:
                    return MsgClientDoneSerializer.INSTANCE.deserialize(bytes);
                default:
                    throw new RuntimeException(String.format("Invalid local app msg notify id: %d", id));
            }
        } catch (Exception e) {
            log.error("LocalAppMsgNotify parsing error", e);
            log.error("Data: {}", HexUtil.encodeHexString(bytes));
            return null;
        }
    }
}
