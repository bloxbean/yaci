package com.bloxbean.cardano.yaci.core.protocol.peersharing.messages;

public enum PeerAddressType {
    IPv4(0),
    IPv6(1);
    
    private final int value;
    
    PeerAddressType(int value) {
        this.value = value;
    }
    
    public int getValue() {
        return value;
    }
    
    public static PeerAddressType fromValue(int value) {
        for (PeerAddressType type : values()) {
            if (type.value == value) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown PeerAddressType: " + value);
    }
}