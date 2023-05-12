package com.bloxbean.cardano.yaci.core.protocol.localstate.api;

public enum Era {
    Byron(0),
    Shelley(1),
    Allegra(2),
    Mary(3),
    Alonzo(4),
    Babbage(5),
    Conway(6);

    public final int value;
    Era(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
