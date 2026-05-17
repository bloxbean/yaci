package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2c.notify;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2c.notify.messages.*;

public enum LocalAppMsgNotifyState implements LocalAppMsgNotifyStateBase {
    Idle {
        @Override
        public State nextState(Message message) {
            if (message instanceof MsgRequestMessages) {
                return ((MsgRequestMessages) message).isBlocking()
                        ? BusyBlocking : BusyNonBlocking;
            } else if (message instanceof MsgClientDone) {
                return Done;
            }
            return this;
        }

        @Override
        public boolean hasAgency(boolean isClient) {
            return isClient;
        }
    },
    BusyNonBlocking {
        @Override
        public State nextState(Message message) {
            if (message instanceof MsgReplyMessagesNonBlocking)
                return Idle;
            return this;
        }

        @Override
        public boolean hasAgency(boolean isClient) {
            return !isClient;
        }
    },
    BusyBlocking {
        @Override
        public State nextState(Message message) {
            if (message instanceof MsgReplyMessagesBlocking)
                return Idle;
            return this;
        }

        @Override
        public boolean hasAgency(boolean isClient) {
            return !isClient;
        }
    },
    Done {
        @Override
        public State nextState(Message message) {
            return this;
        }

        @Override
        public boolean hasAgency(boolean isClient) {
            return false;
        }
    }
}
