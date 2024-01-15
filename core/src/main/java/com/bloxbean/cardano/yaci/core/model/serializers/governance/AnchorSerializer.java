package com.bloxbean.cardano.yaci.core.model.serializers.governance;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.SimpleValue;
import com.bloxbean.cardano.yaci.core.model.governance.Anchor;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

import java.util.List;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toHex;
import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toUnicodeString;

public enum AnchorSerializer implements Serializer<Anchor> {
    INSTANCE;

    @Override
    public Anchor deserializeDI(DataItem di) {
        if (di == null || di == SimpleValue.NULL)
            return null;

        return deserializeAnchor((Array) di);
    }

    /**
     * anchor =
     * [ anchor_url       : url
     * , anchor_data_hash : $hash32
     * ]
     *
     * @param array
     * @return
     */
    private Anchor deserializeAnchor(Array array) {
        if (array != null && array.getDataItems().size() != 2)
            throw new IllegalArgumentException("Invalid anchor array. Expected 2 items. Found : "
                    + array.getDataItems().size());

        List<DataItem> diList = array.getDataItems();
        String anchorUrl = toUnicodeString(diList.get(0));
        String anchorDataHash = toHex(diList.get(1));

        return new Anchor(anchorUrl, anchorDataHash);
    }
}
