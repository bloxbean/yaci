package com.bloxbean.cardano.yaci.core.protocol.leiosfetch;

import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.protocol.leios.serializers.LeiosCborUtil;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosFetchError;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.bloxbean.cardano.yaci.core.protocol.leiosfetch.serializers.LeiosFetchSerializers.*;

public interface LeiosFetchStateBase extends State {
    Logger log = LoggerFactory.getLogger(LeiosFetchStateBase.class);

    @Override
    default Message handleInbound(byte[] bytes) {
        return deserialize(bytes);
    }

    static Message deserialize(byte[] bytes) {
        try {
            DataItem[] dataItems = CborSerializationUtil.deserialize(bytes);
            if (dataItems.length != 1) {
                throw new IllegalArgumentException("LeiosFetch message must contain one top-level CBOR value");
            }
            DataItem dataItem = dataItems[0];
            int tag = LeiosCborUtil.readTag(dataItem);
            switch (tag) {
                case 0:
                    return MsgLeiosBlockRequestSerializer.INSTANCE.deserializeDI(dataItem);
                case 1:
                    return MsgLeiosBlockSerializer.INSTANCE.deserializeDI(dataItem);
                case 2:
                    return MsgLeiosBlockTxsRequestSerializer.INSTANCE.deserializeDI(dataItem);
                case 3:
                    return MsgLeiosBlockTxsSerializer.INSTANCE.deserializeDI(dataItem);
                case 9:
                    return MsgClientDoneSerializer.INSTANCE.deserializeDI(dataItem);
                default:
                    throw new UnsupportedOperationException("LeiosFetch tag " + tag + " is not implemented");
            }
        } catch (Exception e) {
            log.error("LeiosFetch parsing error", e);
            if (log.isDebugEnabled()) {
                log.debug("Data: {}", HexUtil.encodeHexString(bytes));
            }
            return new MsgLeiosFetchError(e);
        }
    }
}
