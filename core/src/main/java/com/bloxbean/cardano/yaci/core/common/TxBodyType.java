package com.bloxbean.cardano.yaci.core.common;

import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era;

public enum TxBodyType {
    ALONZO(4), BABBAGE(5), CONWAY(6);

    public final int value;
    TxBodyType(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public Era getEra() {
        switch (this) {
            case ALONZO:
                return Era.Alonzo;
            case BABBAGE:
                return Era.Babbage;
            case CONWAY:
                return Era.Conway;
            default:
                throw new IllegalArgumentException("Unknown TxBodyType: " + this);
        }
    }
}
