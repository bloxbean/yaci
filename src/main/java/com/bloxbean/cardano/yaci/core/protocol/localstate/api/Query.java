package com.bloxbean.cardano.yaci.core.protocol.localstate.api;

import co.nstant.in.cbor.model.DataItem;

public interface Query<T extends QueryResult> {
    DataItem serialize();
    T deserializeResult(DataItem di);
}
