package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.yaci.core.model.conway.VotingProcedures;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

public enum VotingProceduresSerializer implements Serializer<VotingProcedures> {
    INSTANCE;

    @Override
    public VotingProcedures deserializeDI(DataItem di) {
        System.out.println("VotingProceduresSerializer.deserializeDI ------");
        return null;
    }
}
