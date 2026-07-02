package com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;

import java.util.Objects;

public class MsgLeiosNotifyError implements Message {
    private final Throwable cause;

    public MsgLeiosNotifyError(Throwable cause) {
        this.cause = Objects.requireNonNull(cause, "cause");
    }

    public Throwable getCause() {
        return cause;
    }
}
