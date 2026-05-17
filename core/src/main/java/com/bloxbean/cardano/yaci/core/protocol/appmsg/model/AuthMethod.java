package com.bloxbean.cardano.yaci.core.protocol.appmsg.model;

import lombok.Getter;

@Getter
public enum AuthMethod {
    OPEN(0),
    PERMISSIONED(1),
    SPO_KES(2),
    DELEGATED(3);

    private final int value;

    AuthMethod(int value) {
        this.value = value;
    }

    public static AuthMethod fromValue(int value) {
        for (AuthMethod method : values()) {
            if (method.value == value) return method;
        }
        throw new IllegalArgumentException("Unknown auth method: " + value);
    }
}
