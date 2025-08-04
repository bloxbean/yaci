package com.bloxbean.cardano.yaci.core.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class SegmentSerializationTest {

    @Test
    void testSegmentSerializationWithResponseFlag() throws IOException {
        // Test protocol ID 0x8002 (ChainSync with response flag)
        Segment segment = Segment.builder()
                .timestamp(12993) // Use the problematic value as timestamp
                .protocol(0x8002)
                .payload(new byte[]{1, 2, 3})
                .build();

        ByteBuf buffer = Unpooled.buffer();
        segment.serialize(buffer);

        // Read back and verify
        assertEquals(12993, buffer.readInt()); // timestamp
        assertEquals(0x8002, buffer.readUnsignedShort()); // protocol
        assertEquals(3, buffer.readUnsignedShort()); // length
        
        // Verify we didn't accidentally write 0x32c1 as protocol
        buffer.readerIndex(4); // Reset to protocol position
        int readProtocol = buffer.readUnsignedShort();
        assertNotEquals(0x32c1, readProtocol);
        assertEquals(0x8002, readProtocol);
    }

    @Test
    void testMultipleSegmentsSerialization() throws IOException {
        ByteBuf buffer = Unpooled.buffer();
        
        // Write multiple segments as they would be in the actual flow
        for (int i = 0; i < 5; i++) {
            Segment segment = Segment.builder()
                    .timestamp(1000 + i)
                    .protocol(0x8002)
                    .payload(new byte[]{(byte)i})
                    .build();
            segment.serialize(buffer);
        }

        // Read back all segments
        for (int i = 0; i < 5; i++) {
            int timestamp = buffer.readInt();
            int protocol = buffer.readUnsignedShort();
            int length = buffer.readUnsignedShort();
            byte[] payload = new byte[length];
            buffer.readBytes(payload);

            assertEquals(1000 + i, timestamp);
            assertEquals(0x8002, protocol);
            assertEquals(1, length);
            assertEquals(i, payload[0]);
        }
    }

    @Test 
    void testTimestampOverflow() throws IOException {
        // Test with timestamp that could cause issues
        Segment segment = Segment.builder()
                .timestamp(0x32c10000) // Large timestamp
                .protocol(0x8002)
                .payload(new byte[0])
                .build();

        ByteBuf buffer = Unpooled.buffer();
        segment.serialize(buffer);

        // Verify the bytes are written correctly
        assertEquals(0x32c10000, buffer.readInt());
        assertEquals(0x8002, buffer.readUnsignedShort());
        assertEquals(0, buffer.readUnsignedShort());
    }
}