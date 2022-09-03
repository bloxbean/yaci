package com.bloxbean.cardano.yaci.core.protocol.localtx;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.serializers.TxSubmissionMessagesSerializers;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface LocalTxSubmissionStateBase extends State {
    Logger log = LoggerFactory.getLogger(LocalTxSubmissionStateBase.class);

    @Override
    default Message handleInbound(byte[] bytes) {
        try {
            DataItem di = CborSerializationUtil.deserialize(bytes);
            Array array = (Array) di;
            int id = ((UnsignedInteger) array.getDataItems().get(0)).getValue().intValue();
            switch (id) {
                case 0:
                    return TxSubmissionMessagesSerializers.InitSerializer.INSTANCE.deserialize(bytes);
                case 1:
                    return TxSubmissionMessagesSerializers.RequestTxIdsSerializer.INSTANCE.deserialize(bytes);
                case 2:
                    return TxSubmissionMessagesSerializers.ReplyTxIdsSerializer.INSTANCE.deserialize(bytes);
                case 3:
                    return TxSubmissionMessagesSerializers.RequestTxsSerializer.INSTANCE.deserialize(bytes);
                default:
                    throw new RuntimeException(String.format("Invalid msg id: %d", id));
            }
        } catch (Exception e) {
            log.error("Parsing error ", e);
            log.error("TxSubmission data");
            log.error(HexUtil.encodeHexString(bytes));
            return null;
        }
    }
}
