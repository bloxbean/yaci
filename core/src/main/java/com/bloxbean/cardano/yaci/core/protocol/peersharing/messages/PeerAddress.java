package com.bloxbean.cardano.yaci.core.protocol.peersharing.messages;

import lombok.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@ToString
@EqualsAndHashCode
public class PeerAddress {
    private PeerAddressType type;
    private String address;
    private int port;
    
    public static PeerAddress ipv4(String address, int port) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 0 and 65535");
        }
        return new PeerAddress(PeerAddressType.IPv4, address, port);
    }
    
    public static PeerAddress ipv6(String address, int port) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 0 and 65535");
        }
        return new PeerAddress(PeerAddressType.IPv6, address, port);
    }
}