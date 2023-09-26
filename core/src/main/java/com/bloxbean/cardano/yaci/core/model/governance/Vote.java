package com.bloxbean.cardano.yaci.core.model.governance;

public enum Vote {
    NO(0), YES(1), ABSTAIN(2);

    private final int value;
    Vote(int value) {
        this.value = value;
    }
}
