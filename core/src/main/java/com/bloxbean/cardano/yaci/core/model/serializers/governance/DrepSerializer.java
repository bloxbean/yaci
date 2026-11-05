package com.bloxbean.cardano.yaci.core.model.serializers.governance;

import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.transaction.raw.RawDRep;
import com.bloxbean.cardano.client.transaction.util.RawModels;
import com.bloxbean.cardano.yaci.core.model.governance.Drep;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

public enum DrepSerializer implements Serializer<Drep> {
    INSTANCE;

    /**
     * @deprecated A decoded DRep has lost its source encoding. Use {@link #read(RawDRep)}.
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    @Override
    public Drep deserializeDI(DataItem di) {
        return read(RawDRep.lenient(RawModels.span(di)));
    }

    public Drep read(RawDRep drep) {
        return switch (drep.kind()) {
            case KEY_HASH -> Drep.addrKeyHash(HexUtil.encodeHexString(drep.hash()));
            case SCRIPT_HASH -> Drep.scriptHash(HexUtil.encodeHexString(drep.hash()));
            case ABSTAIN -> Drep.abstain();
            case NO_CONFIDENCE -> Drep.noConfidence();
        };
    }
}
