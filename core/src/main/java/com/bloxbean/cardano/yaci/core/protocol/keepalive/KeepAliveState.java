package com.bloxbean.cardano.yaci.core.protocol.keepalive;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.keepalive.messages.MsgDone;
import com.bloxbean.cardano.yaci.core.protocol.keepalive.messages.MsgKeepAlive;

public enum KeepAliveState implements KeepAliveStateBase {
    Client {
        @Override
        public KeepAliveState nextState(Message message) {
            if (message instanceof MsgKeepAlive)
                return Server;
            else if (message instanceof MsgDone)
                return Done;
            else
                return this;
        }

        @Override
        public boolean hasAgency(boolean isClient) {
            return isClient;
        }
    },
    Server {
        @Override
        public KeepAliveState nextState(Message message) {
            return Client;
        }

        @Override
        public boolean hasAgency(boolean isClient) {
            return !isClient;
        }
    },
    Done {
        @Override
        public KeepAliveState nextState(Message message) {
            return this;
        }

        @Override
        public boolean hasAgency(boolean isClient) {
            return !isClient;
        }
    }
}
