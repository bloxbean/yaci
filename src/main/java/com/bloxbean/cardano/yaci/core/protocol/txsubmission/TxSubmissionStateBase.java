package com.bloxbean.cardano.yaci.core.protocol.txsubmission;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.bloxbean.cardano.yaci.core.protocol.txsubmission.serializers.TxSubmissionMessagesSerializers.*;

public interface TxSubmissionStateBase extends State {
    Logger log = LoggerFactory.getLogger(TxSubmissionStateBase.class);

    default Message handleInbound(byte[] bytes) {
        try {
            DataItem di = CborSerializationUtil.deserialize(bytes);
            Array array = (Array) di;
            int id = ((UnsignedInteger) array.getDataItems().get(0)).getValue().intValue();
            switch (id) {
                case 6:
                    return InitSerializer.INSTANCE.deserialize(bytes);
                case 0:
                    return RequestTxIdsSerializer.INSTANCE.deserialize(bytes);
                case 1:
                    return ReplyTxIdsSerializer.INSTANCE.deserialize(bytes);
                case 2:
                    return RequestTxsSerializer.INSTANCE.deserialize(bytes);
                case 3:
                    return ReplyTxsSerializer.INSTANCE.deserialize(bytes);
                case 4:
                    return MsgDoneSerializer.INSTANCE.deserialize(bytes);
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
