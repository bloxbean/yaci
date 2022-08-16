package com.bloxbean.cardano.yaci.core.protocol.chainsync.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.serializers.FindIntersectSerializer;
import lombok.Getter;

@Getter
public class FindIntersect implements Message {
    private Point[] points;

    public FindIntersect(Point[] points) {
        this.points = points;
    }

    @Override
    public byte[] serialize() {
        return FindIntersectSerializer.INSTANCE.serialize(this);
    }
}

