package com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages;

import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosRawCbor;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.serializers.LeiosFetchSerializers;

import java.util.Objects;

public class MsgLeiosBlock implements Message {
    private final LeiosRawCbor wirePayload;
    private final LeiosRawCbor endorserBlock;

    public MsgLeiosBlock(LeiosRawCbor wirePayload) {
        this.wirePayload = Objects.requireNonNull(wirePayload, "wirePayload");
        CborSpan field = CborSpan.of(wirePayload.getCbor());
        if (field.tag() == 24) {
            this.endorserBlock = LeiosRawCbor.of(field.embedded().bytes());
        } else {
            // Musashi w42 sends the reference map directly.
            this.endorserBlock = wirePayload;
        }
    }

    /** The exact bytes covered by the size and hash in the endorser-block announcement. */
    public LeiosRawCbor getEndorserBlock() {
        return endorserBlock;
    }

    /** The encoded field as received, including the tag-24 envelope when present. */
    public LeiosRawCbor getWirePayload() {
        return wirePayload;
    }

    @Override
    public byte[] serialize() {
        return LeiosFetchSerializers.MsgLeiosBlockSerializer.INSTANCE.serialize(this);
    }
}
