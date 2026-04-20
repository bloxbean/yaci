package com.bloxbean.cardano.yaci.core.protocol;

import java.util.Collections;
import java.util.List;

public interface State {
    State nextState(Message message);

    boolean hasAgency(boolean isClient);

    default Message handleInbound(byte[] bytes) {
        return null;
    }

    /**
     * List of allowed message types
     *
     * @return
     */
    default <T extends Message> List<Class<T>> allowedMessageTypes() {
        return Collections.EMPTY_LIST;
    }

    /**
     * Verify if the message is allowed in the current state.
     * Throws {@link IllegalStateException} if the message is not allowed.
     * Ignores if {@link #allowedMessageTypes()} is not implemented for the state,
     *
     * @param message
     * @param <T>
     */
    default <T extends Message> void verifyMessageType(T message) {
        if (allowedMessageTypes() != null && !allowedMessageTypes().isEmpty()) {
            if (!allowedMessageTypes().contains(message.getClass()))
                throw new IllegalStateException("Current state [" + this + "] doesn't support this message : " + message);
        }
    }

}
