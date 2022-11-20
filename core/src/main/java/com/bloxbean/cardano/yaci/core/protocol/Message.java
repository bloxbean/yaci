package com.bloxbean.cardano.yaci.core.protocol;

public interface Message<T> {
    //Implement this method wherever we are the sender
    default byte[] serialize() {
        return new byte[0];
    }
}

