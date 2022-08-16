package com.bloxbean.cardano.yaci.core.protocol;

import io.netty.buffer.ByteBuf;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Slf4j
public class Segment {
    private int timestamp;
    private short protocol;
    private byte[] payload;

    public void serialize(ByteBuf out) throws IOException {
        out.writeInt(timestamp);
        out.writeShort(protocol);
        out.writeShort(payload.length);
        out.writeBytes(payload);
    }

    public static Segment deserialize(ByteBuf in) {
        int timestamp = (int) in.readUnsignedInt();
        int protocol = in.readShort();

        //TODO -- check the following logic for unsigned
        if (protocol < 0)
            protocol = protocol + 32768;

        int payloadLen = in.readUnsignedShort();

        if (log.isTraceEnabled()) {
            log.trace("---------------- PAYLOAD LENGTH >>>> " + payloadLen);
            log.trace("----- timestamp---- " + timestamp);
            log.trace("------ protocol ----" + protocol);
        }

        //payload
        byte[] payload = new byte[payloadLen];
        in.readBytes(payload);

        Segment segment = Segment.builder()
                .timestamp(timestamp)
                .protocol((short) protocol)
                .payload(payload)
                .build();

       if (log.isTraceEnabled()) {
           log.trace("Segment : {}", segment);
       }

        return segment;
    }
}
