package com.bloxbean.cardano.yaci.core.protocol.peersharing;

import com.bloxbean.cardano.yaci.core.protocol.peersharing.messages.*;
import com.bloxbean.cardano.yaci.core.protocol.peersharing.serializers.PeerSharingSerializers;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PeerSharingSerializersTest {

    @Test
    public void testMsgShareRequestSerialization() {
        MsgShareRequest request = new MsgShareRequest(10);
        
        byte[] serialized = request.serialize();
        assertNotNull(serialized);
        
        MsgShareRequest deserialized = PeerSharingSerializers.MsgShareRequestSerializer.INSTANCE
            .deserializeDI(CborSerializationUtil.deserializeOne(serialized));
        
        assertEquals(request.getAmount(), deserialized.getAmount());
    }

    @Test
    public void testMsgShareRequestMaxAmount() {
        // Test maximum amount (255 for CDDL word8)
        MsgShareRequest request = new MsgShareRequest(255);
        
        byte[] serialized = request.serialize();
        MsgShareRequest deserialized = PeerSharingSerializers.MsgShareRequestSerializer.INSTANCE
            .deserializeDI(CborSerializationUtil.deserializeOne(serialized));
        
        assertEquals(255, deserialized.getAmount());
    }

    @Test
    public void testMsgShareRequestInvalidAmount() {
        // Test invalid amounts
        assertThrows(IllegalArgumentException.class, () -> new MsgShareRequest(-1));
        assertThrows(IllegalArgumentException.class, () -> new MsgShareRequest(256));
    }

    @Test
    public void testMsgSharePeersSerializationIPv4() {
        PeerAddress ipv4Peer = PeerAddress.ipv4("192.168.1.1", 3001);
        List<PeerAddress> peers = Arrays.asList(ipv4Peer);
        MsgSharePeers sharePeers = new MsgSharePeers(peers);
        
        byte[] serialized = sharePeers.serialize();
        assertNotNull(serialized);
        
        MsgSharePeers deserialized = PeerSharingSerializers.MsgSharePeersSerializer.INSTANCE
            .deserializeDI(CborSerializationUtil.deserializeOne(serialized));
        
        assertEquals(1, deserialized.getPeerAddresses().size());
        PeerAddress deserializedPeer = deserialized.getPeerAddresses().get(0);
        assertEquals(PeerAddressType.IPv4, deserializedPeer.getType());
        assertEquals("192.168.1.1", deserializedPeer.getAddress());
        assertEquals(3001, deserializedPeer.getPort());
    }

    @Test
    public void testMsgSharePeersSerializationIPv6() {
        PeerAddress ipv6Peer = PeerAddress.ipv6("2001:db8::1", 3001);
        List<PeerAddress> peers = Arrays.asList(ipv6Peer);
        MsgSharePeers sharePeers = new MsgSharePeers(peers);
        
        byte[] serialized = sharePeers.serialize();
        assertNotNull(serialized);
        
        MsgSharePeers deserialized = PeerSharingSerializers.MsgSharePeersSerializer.INSTANCE
            .deserializeDI(CborSerializationUtil.deserializeOne(serialized));
        
        assertEquals(1, deserialized.getPeerAddresses().size());
        PeerAddress deserializedPeer = deserialized.getPeerAddresses().get(0);
        assertEquals(PeerAddressType.IPv6, deserializedPeer.getType());
        assertEquals("2001:db8::1", deserializedPeer.getAddress());
        assertEquals(3001, deserializedPeer.getPort());
    }

    @Test
    public void testMsgSharePeersSerializationMixed() {
        PeerAddress ipv4Peer = PeerAddress.ipv4("10.0.0.1", 3001);
        PeerAddress ipv6Peer = PeerAddress.ipv6("::1", 8080);
        List<PeerAddress> peers = Arrays.asList(ipv4Peer, ipv6Peer);
        MsgSharePeers sharePeers = new MsgSharePeers(peers);
        
        byte[] serialized = sharePeers.serialize();
        assertNotNull(serialized);
        
        MsgSharePeers deserialized = PeerSharingSerializers.MsgSharePeersSerializer.INSTANCE
            .deserializeDI(CborSerializationUtil.deserializeOne(serialized));
        
        assertEquals(2, deserialized.getPeerAddresses().size());
        
        // Check first peer (IPv4)
        PeerAddress peer1 = deserialized.getPeerAddresses().get(0);
        assertEquals(PeerAddressType.IPv4, peer1.getType());
        assertEquals("10.0.0.1", peer1.getAddress());
        assertEquals(3001, peer1.getPort());
        
        // Check second peer (IPv6)
        PeerAddress peer2 = deserialized.getPeerAddresses().get(1);
        assertEquals(PeerAddressType.IPv6, peer2.getType());
        assertEquals("0:0:0:0:0:0:0:1", peer2.getAddress()); // Canonical form
        assertEquals(8080, peer2.getPort());
    }

    @Test
    public void testMsgDoneSerialization() {
        MsgDone done = new MsgDone();
        
        byte[] serialized = done.serialize();
        assertNotNull(serialized);
        
        MsgDone deserialized = PeerSharingSerializers.MsgDoneSerializer.INSTANCE
            .deserializeDI(CborSerializationUtil.deserializeOne(serialized));
        
        assertNotNull(deserialized);
    }

    @Test
    public void testPeerAddressValidation() {
        // Test valid IPv4
        PeerAddress ipv4 = PeerAddress.ipv4("127.0.0.1", 3001);
        assertEquals(PeerAddressType.IPv4, ipv4.getType());
        assertEquals("127.0.0.1", ipv4.getAddress());
        assertEquals(3001, ipv4.getPort());
        
        // Test valid IPv6
        PeerAddress ipv6 = PeerAddress.ipv6("::1", 8080);
        assertEquals(PeerAddressType.IPv6, ipv6.getType());
        assertEquals("::1", ipv6.getAddress());
        assertEquals(8080, ipv6.getPort());
        
        // Test invalid ports
        assertThrows(IllegalArgumentException.class, () -> PeerAddress.ipv4("127.0.0.1", -1));
        assertThrows(IllegalArgumentException.class, () -> PeerAddress.ipv4("127.0.0.1", 65536));
        assertThrows(IllegalArgumentException.class, () -> PeerAddress.ipv6("::1", -1));
        assertThrows(IllegalArgumentException.class, () -> PeerAddress.ipv6("::1", 65536));
    }

    @Test
    public void testPeerAddressTypeEnum() {
        assertEquals(0, PeerAddressType.IPv4.getValue());
        assertEquals(1, PeerAddressType.IPv6.getValue());
        
        assertEquals(PeerAddressType.IPv4, PeerAddressType.fromValue(0));
        assertEquals(PeerAddressType.IPv6, PeerAddressType.fromValue(1));
        
        assertThrows(IllegalArgumentException.class, () -> PeerAddressType.fromValue(2));
        assertThrows(IllegalArgumentException.class, () -> PeerAddressType.fromValue(-1));
    }

    @Test
    public void testEmptyPeerList() {
        MsgSharePeers emptyShare = new MsgSharePeers(Arrays.asList());
        
        byte[] serialized = emptyShare.serialize();
        assertNotNull(serialized);
        
        MsgSharePeers deserialized = PeerSharingSerializers.MsgSharePeersSerializer.INSTANCE
            .deserializeDI(CborSerializationUtil.deserializeOne(serialized));
        
        assertNotNull(deserialized.getPeerAddresses());
        assertEquals(0, deserialized.getPeerAddresses().size());
    }
}