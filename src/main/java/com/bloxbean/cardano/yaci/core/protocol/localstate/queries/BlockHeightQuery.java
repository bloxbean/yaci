package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Query;

import java.util.List;

public class BlockHeightQuery implements Query<BlockHeightQueryResult> {
    @Override
    public DataItem serialize() {
        Array array = new Array();
        array.add(new UnsignedInteger(2));

        return array;
    }

    @Override
    public BlockHeightQueryResult deserializeResult(DataItem di) {
        List<DataItem> dataItemList = ((Array)di).getDataItems();

        int type = ((UnsignedInteger)dataItemList.get(0)).getValue().intValue(); //4

        List<DataItem> resultDIList = ((Array)dataItemList.get(1)).getDataItems();

        int firstElm = ((UnsignedInteger)resultDIList.get(0)).getValue().intValue();
        long blockHeight = ((UnsignedInteger)resultDIList.get(1)).getValue().intValue();

        return new BlockHeightQueryResult(blockHeight);
    }
}
