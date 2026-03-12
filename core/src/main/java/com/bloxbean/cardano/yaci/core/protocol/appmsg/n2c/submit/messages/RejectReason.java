package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2c.submit.messages;

import lombok.Getter;

@Getter
public enum RejectReason {
    INVALID(0),
    ALREADY_RECEIVED(1),
    EXPIRED(2),
    OTHER(3);

    private final int value;

    RejectReason(int value) {
        this.value = value;
    }

    public static RejectReason fromValue(int value) {
        for (RejectReason r : values()) {
            if (r.value == value) return r;
        }
        return OTHER;
    }
}
