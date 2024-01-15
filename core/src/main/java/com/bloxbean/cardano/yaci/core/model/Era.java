package com.bloxbean.cardano.yaci.core.model;

public enum Era {
    Byron(1),
    Shelley(2),
    Allegra(3),
    Mary(4),
    Alonzo(5),
    Babbage(6),
    CONWAY(7);

    public final int value;
    Era(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
