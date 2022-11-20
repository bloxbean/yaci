package com.bloxbean.cardano.yaci.core.protocol.localstate.serializers;

import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.localstate.messages.MsgResult;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

import java.util.List;

public enum MsgResultSerializer implements Serializer<MsgResult> {
    INSTANCE;

    @Override
    public MsgResult deserializeDI(DataItem di) {
        return deserializeDI(new DataItem[]{di});
    }

    @Override
    public MsgResult deserializeDI(DataItem[] di) {
        List<DataItem> dataItemList = checkMsgType(di[0], 4);

//        QueryResult result = QueryResultDeserializer.deserialize(dataItemList.get(1));
        return new MsgResult(CborSerializationUtil.serialize(di, false));
    }

    //TODO -- serialize() is not required now as it's used in server
}
