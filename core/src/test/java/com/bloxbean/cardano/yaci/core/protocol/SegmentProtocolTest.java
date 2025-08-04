package com.bloxbean.cardano.yaci.core.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class SegmentProtocolTest {
    
    @Test
    public void testProtocolIdSerialization() {
        // Test with BlockFetch server response (3 | 0x8000 = 0x8003)
        int protocolId = 3 | 0x8000;
        System.out.println("Original protocol ID: " + protocolId + " (0x" + Integer.toHexString(protocolId) + ")");
        
        Segment segment = Segment.builder()
                .timestamp(12345)
                .protocol(protocolId)
                .payload(new byte[]{1, 2, 3})
                .build();
        
        ByteBuf buf = Unpooled.buffer();
        try {
            segment.serialize(buf);
            
            // Read back
            Segment deserialized = Segment.deserialize(buf);
            
            System.out.println("Deserialized protocol ID: " + deserialized.getProtocol() + 
                              " (0x" + Integer.toHexString(deserialized.getProtocol()) + ")");
            
            assertEquals(protocolId, deserialized.getProtocol());
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            buf.release();
        }
    }
    
    @Test
    public void testSuspiciousProtocolId() {
        // Test with the suspicious value 0x32c1
        int suspiciousId = 0x32c1;
        System.out.println("Testing suspicious ID: " + suspiciousId + " (0x" + Integer.toHexString(suspiciousId) + ")");
        
        // Let's see what happens if we treat 0x32c1 as if it were meant to be 3
        // Maybe there's some bit shifting or corruption happening
        
        // Check if 0x32c1 could be a result of some bit manipulation
        System.out.println("0x32c1 >> 8 = " + (suspiciousId >> 8) + " (0x" + Integer.toHexString(suspiciousId >> 8) + ")");
        System.out.println("0x32c1 & 0xFF = " + (suspiciousId & 0xFF) + " (0x" + Integer.toHexString(suspiciousId & 0xFF) + ")");
        System.out.println("0x32c1 & 0x7FFF = " + (suspiciousId & 0x7FFF) + " (0x" + Integer.toHexString(suspiciousId & 0x7FFF) + ")");
        
        // Check if bytes are swapped
        int swapped = ((suspiciousId & 0xFF) << 8) | ((suspiciousId >> 8) & 0xFF);
        System.out.println("Byte swapped: " + swapped + " (0x" + Integer.toHexString(swapped) + ")");
    }
}