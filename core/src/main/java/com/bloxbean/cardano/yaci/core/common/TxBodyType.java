package com.bloxbean.cardano.yaci.core.common;

public enum TxBodyType {
    ALONZO(4), BABBAGE(5), CONWAY(6);

    public final int value;
    TxBodyType(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
