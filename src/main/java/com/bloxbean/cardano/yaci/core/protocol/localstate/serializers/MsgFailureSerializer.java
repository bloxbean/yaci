package com.bloxbean.cardano.yaci.core.protocol.localstate.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.localstate.messages.MsgFailure;

import java.util.List;

public enum MsgFailureSerializer implements Serializer<MsgFailure> {
    INSTANCE;

    @Override
    public MsgFailure deserializeDI(DataItem di) {
        List<DataItem> dataItemList = ((Array) di).getDataItems();
        int key = ((UnsignedInteger)dataItemList.get(0)).getValue().intValue();
        if (key != 2)
            throw new IllegalStateException("Invalid key. Expected : 2, Found: " + key);

        int failureCode = ((UnsignedInteger) dataItemList.get(1)).getValue().intValue();
        MsgFailure.Reason reason = null;

        if (failureCode == 0)
            reason = MsgFailure.Reason.ACQUIRE_FAILURE_POINT_TOO_OLD;
        else if (failureCode == 1)
            reason = MsgFailure.Reason.ACQUIRE_FAILURE_POINT_NOT_ON_CHAIN;

        return new MsgFailure(reason);

    }

    //TODO -- serialize() is not required now as it's used in server
}
