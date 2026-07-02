package com.bloxbean.cardano.yaci.core.protocol.leiosnotify;

import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.protocol.leios.serializers.LeiosCborUtil;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosNotifyError;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.bloxbean.cardano.yaci.core.protocol.leiosnotify.serializers.LeiosNotifySerializers.*;

public interface LeiosNotifyStateBase extends State {
    Logger log = LoggerFactory.getLogger(LeiosNotifyStateBase.class);

    @Override
    default Message handleInbound(byte[] bytes) {
        return deserialize(bytes);
    }

    static Message deserialize(byte[] bytes) {
        try {
            DataItem[] dataItems = CborSerializationUtil.deserialize(bytes);
            if (dataItems.length != 1) {
                throw new IllegalArgumentException("LeiosNotify message must contain one top-level CBOR value");
            }
            DataItem dataItem = dataItems[0];
            int tag = LeiosCborUtil.readTag(dataItem);
            switch (tag) {
                case 0:
                    return MsgLeiosNotificationRequestNextSerializer.INSTANCE.deserializeDI(dataItem);
                case 1:
                    return MsgLeiosBlockAnnouncementSerializer.INSTANCE.deserializeDI(dataItem);
                case 2:
                    return MsgLeiosBlockOfferSerializer.INSTANCE.deserializeDI(dataItem);
                case 3:
                    return MsgLeiosBlockTxsOfferSerializer.INSTANCE.deserializeDI(dataItem);
                case 4:
                    return MsgLeiosVotesSerializer.INSTANCE.deserializeDI(dataItem);
                case 5:
                    return MsgClientDoneSerializer.INSTANCE.deserializeDI(dataItem);
                default:
                    throw new IllegalArgumentException("Invalid LeiosNotify message tag: " + tag);
            }
        } catch (Exception e) {
            log.error("LeiosNotify parsing error", e);
            if (log.isDebugEnabled()) {
                log.debug("Data: {}", HexUtil.encodeHexString(bytes));
            }
            return new MsgLeiosNotifyError(e);
        }
    }
}
