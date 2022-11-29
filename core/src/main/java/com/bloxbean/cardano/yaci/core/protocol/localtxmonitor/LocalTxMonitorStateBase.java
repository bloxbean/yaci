package com.bloxbean.cardano.yaci.core.protocol.localtxmonitor;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.protocol.localtxmonitor.serializers.LocalTxMonitorSerializers;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface LocalTxMonitorStateBase extends State {
    Logger log = LoggerFactory.getLogger(LocalTxMonitorStateBase.class);

    @Override
    default Message handleInbound(byte[] bytes) {
        try {
            DataItem di = CborSerializationUtil.deserializeOne(bytes);
            Array array = (Array) di;
            int id = ((UnsignedInteger) array.getDataItems().get(0)).getValue().intValue();
            switch (id) {
                case 0:
                    return LocalTxMonitorSerializers.MsgDoneSerializer.INSTANCE.deserialize(bytes);
                case 1:
                    return LocalTxMonitorSerializers.MsgAcquireSerializer.INSTANCE.deserialize(bytes);
                case 2:
                    return LocalTxMonitorSerializers.MsgAcquiredSerializer.INSTANCE.deserialize(bytes);
                case 3:
                    return LocalTxMonitorSerializers.MsgReleaseSerializer.INSTANCE.deserialize(bytes);
                case 5:
                    return LocalTxMonitorSerializers.MsgNextTxSerializer.INSTANCE.deserialize(bytes);
                case 6:
                    return LocalTxMonitorSerializers.MsgReplyNextTxSerializer.INSTANCE.deserialize(bytes);
                case 7:
                    return LocalTxMonitorSerializers.MsgHasTxSerializer.INSTANCE.deserialize(bytes);
                case 8:
                    return LocalTxMonitorSerializers.MsgReplyHasTxSerializer.INSTANCE.deserialize(bytes);
                case 9:
                    return LocalTxMonitorSerializers.MsgGetSizesSerializer.INSTANCE.deserialize(bytes);
                case 10:
                    return LocalTxMonitorSerializers.MsgReplyGetSizesSerializer.INSTANCE.deserialize(bytes);
                default:
                    throw new RuntimeException(String.format("Invalid msg id: %d", id));
            }
        } catch (Exception e) {
            log.error("Parsing error ", e);
            log.error("TxMonitor data");
            log.error(HexUtil.encodeHexString(bytes));
            return null;
        }
    }
}
