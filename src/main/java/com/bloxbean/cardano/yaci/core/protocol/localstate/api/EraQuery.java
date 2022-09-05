package com.bloxbean.cardano.yaci.core.protocol.localstate.api;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.model.Era;

import java.util.List;

public interface EraQuery<T extends QueryResult> extends Query<T> {

    Era getEra();

    default Array wrapWithOuterArray(Array queryArray) {
        Array firstArr = new Array();
        firstArr.add(new UnsignedInteger(0));

        Array secondArray = new Array();
        secondArray.add(new UnsignedInteger(0));

        Array eraArray = new Array();
        eraArray.add(new UnsignedInteger(getEra().getValue()));
        eraArray.add(queryArray);

        secondArray.add(eraArray);
        firstArr.add(secondArray);

        return firstArr;
    }

    default List<DataItem> extractResultArray(DataItem di) {
        List<DataItem> dataItemList = ((Array)di).getDataItems();

        int type = ((UnsignedInteger)dataItemList.get(0)).getValue().intValue(); //4
        return ((Array)dataItemList.get(1)).getDataItems();
    }

}
