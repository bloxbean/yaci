package com.bloxbean.cardano.yaci.core.protocol.localstate.api;

import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;

public interface Query<T extends QueryResult> {
    DataItem serialize();
    T deserializeResult(DataItem[] di);

    default T deserializeResult(AcceptVersion handshakeVersion, DataItem[] di) {
        return deserializeResult(di);
    }
}
