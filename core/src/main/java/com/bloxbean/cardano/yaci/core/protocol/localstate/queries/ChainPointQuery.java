package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Query;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.util.List;

public class ChainPointQuery implements Query<ChainPointQueryResult> {
    @Override
    public DataItem serialize(AcceptVersion protocolVersion) {
        Array array = new Array();
        array.add(new UnsignedInteger(3));

        return array;
    }

    @Override
    public ChainPointQueryResult deserializeResult(AcceptVersion protocolVersion, DataItem[] di) {
        List<DataItem> dataItemList = ((Array)di[0]).getDataItems();

        int type = ((UnsignedInteger)dataItemList.get(0)).getValue().intValue(); //4

        List<DataItem> resultDIList = ((Array)dataItemList.get(1)).getDataItems();

        long slot = ((UnsignedInteger)resultDIList.get(0)).getValue().longValue();
        String blockHeight = HexUtil.encodeHexString(((ByteString)resultDIList.get(1)).getBytes());

        return new ChainPointQueryResult(new Point(slot, blockHeight));
    }
}
