package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.*;

public enum AppMsgSubmissionState implements AppMsgSubmissionStateBase {
    Init {
        @Override
        public State nextState(Message message) {
            if (message instanceof MsgInit)
                return Idle;
            return this;
        }

        @Override
        public boolean hasAgency(boolean isClient) {
            return isClient;
        }
    },
    Idle {
        @Override
        public State nextState(Message message) {
            if (message instanceof MsgRequestMessageIds) {
                return ((MsgRequestMessageIds) message).isBlocking()
                        ? MessageIdsBlocking : MessageIdsNonBlocking;
            } else if (message instanceof MsgRequestMessages) {
                return Messages;
            } else if (message instanceof MsgDone) {
                return Done;
            }
            return this;
        }

        @Override
        public boolean hasAgency(boolean isClient) {
            return !isClient;
        }
    },
    MessageIdsBlocking {
        @Override
        public State nextState(Message message) {
            if (message instanceof MsgReplyMessageIds)
                return Idle;
            return this;
        }

        @Override
        public boolean hasAgency(boolean isClient) {
            return isClient;
        }
    },
    MessageIdsNonBlocking {
        @Override
        public State nextState(Message message) {
            if (message instanceof MsgReplyMessageIds)
                return Idle;
            return this;
        }

        @Override
        public boolean hasAgency(boolean isClient) {
            return isClient;
        }
    },
    Messages {
        @Override
        public State nextState(Message message) {
            if (message instanceof MsgReplyMessages)
                return Idle;
            return this;
        }

        @Override
        public boolean hasAgency(boolean isClient) {
            return isClient;
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
