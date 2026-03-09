package com.bloxbean.cardano.yaci.core.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SegmentProtocolIdTest {

    @Test
    void testSegmentSerializationWithResponseFlag() throws Exception {
        // Test that protocol ID with response flag (0x8000) is properly handled
        int protocolId = 3; // BlockFetch protocol
        int protocolWithFlag = protocolId | 0x8000; // 32771
        
        Segment segment = Segment.builder()
                .timestamp(1234)
                .protocol(protocolWithFlag)
                .payload(new byte[]{1, 2, 3})
                .build();
        
        // Serialize
        ByteBuf buffer = Unpooled.buffer();
        segment.serialize(buffer);
        
        // Deserialize
        Segment deserialized = Segment.deserialize(buffer);
        
        // Verify protocol ID is preserved correctly
        assertEquals(protocolWithFlag, deserialized.getProtocol());
        assertEquals(32771, deserialized.getProtocol());
        assertEquals(1234, deserialized.getTimestamp());
        assertEquals(3, deserialized.getPayload().length);
    }
    
    @Test
    void testSegmentSerializationWithoutResponseFlag() throws Exception {
        // Test normal protocol ID without response flag
        int protocolId = 3; // BlockFetch protocol
        
        Segment segment = Segment.builder()
                .timestamp(5678)
                .protocol(protocolId)
                .payload(new byte[]{4, 5, 6, 7})
                .build();
        
        // Serialize
        ByteBuf buffer = Unpooled.buffer();
        segment.serialize(buffer);
        
        // Deserialize
        Segment deserialized = Segment.deserialize(buffer);
        
        // Verify protocol ID is preserved correctly
        assertEquals(protocolId, deserialized.getProtocol());
        assertEquals(3, deserialized.getProtocol());
        assertEquals(5678, deserialized.getTimestamp());
        assertEquals(4, deserialized.getPayload().length);
    }
}