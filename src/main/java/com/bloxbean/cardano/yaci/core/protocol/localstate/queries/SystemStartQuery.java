package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Query;

import java.util.List;

public class SystemStartQuery implements Query<SystemStartResult> {

    @Override
    public DataItem serialize() {
        Array array = new Array();
        array.add(new UnsignedInteger(1));

        return array;
    }

    @Override
    public SystemStartResult deserializeResult(DataItem di) {
        List<DataItem> dataItemList = ((Array)di).getDataItems();

        int type = ((UnsignedInteger)dataItemList.get(0)).getValue().intValue(); //4

        List<DataItem> resultDIList = ((Array)dataItemList.get(1)).getDataItems();
        int year = ((UnsignedInteger)resultDIList.get(0)).getValue().intValue();
        int dayOfYear = ((UnsignedInteger)resultDIList.get(1)).getValue().intValue();
        long picoSecondsOfDay = ((UnsignedInteger)resultDIList.get(2)).getValue().intValue();

        return new SystemStartResult(year, dayOfYear, picoSecondsOfDay);
    }
}
