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
    private int protocol;
    private byte[] payload;

    public void serialize(ByteBuf out) throws IOException {
        // Log what we're about to write for debugging
        if (log.isDebugEnabled()) {
            log.debug("Serializing segment - timestamp: {} (0x{}), protocol: {} (0x{}), payload length: {}",
                     timestamp, Integer.toHexString(timestamp),
                     protocol, Integer.toHexString(protocol),
                     payload.length);
        }
        
        out.writeInt(timestamp);
        int protocolToWrite = protocol & 0xFFFF;
        out.writeShort(protocolToWrite);
        out.writeShort(payload.length);
        out.writeBytes(payload);
        
        // Log the actual bytes written
        if (log.isDebugEnabled()) {
            log.debug("Wrote bytes - timestamp: 0x{}, protocol: 0x{}, length: 0x{}",
                     Integer.toHexString(timestamp),
                     Integer.toHexString(protocolToWrite),
                     Integer.toHexString(payload.length));
        }
    }

    public static Segment deserialize(ByteBuf in) {
        int timestamp = (int) in.readUnsignedInt();
        int protocol = in.readUnsignedShort(); // Read as unsigned to handle response flag properly
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
                .protocol(protocol)  // Don't cast to short, keep as int
                .payload(payload)
                .build();

       if (log.isTraceEnabled()) {
           log.trace("Segment : {}", segment);
       }

        return segment;
    }
}
