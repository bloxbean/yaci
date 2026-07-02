package com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;

import java.util.Objects;

public class MsgLeiosFetchError implements Message {
    private final Throwable cause;

    public MsgLeiosFetchError(Throwable cause) {
        this.cause = Objects.requireNonNull(cause, "cause");
    }

    public Throwable getCause() {
        return cause;
    }
}
