package com.bloxbean.cardano.yaci.core.protocol.localtxmonitor.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;

import java.util.UUID;

public class MsgQuery<T> implements Message<T> {
    protected String id;
    public MsgQuery() {
        id = UUID.randomUUID().toString();
    }

    public String getId() {
        return id;
    }
}
