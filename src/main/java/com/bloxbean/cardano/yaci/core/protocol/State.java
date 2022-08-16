package com.bloxbean.cardano.yaci.core.protocol;

public interface State {
    State nextState(Message message);
    boolean hasAgency();

    default Message handleInbound(byte[] bytes) {
        return null;
    }

    default Message handleOutbound(Message message) { //TODO
        return null;
    }

}
