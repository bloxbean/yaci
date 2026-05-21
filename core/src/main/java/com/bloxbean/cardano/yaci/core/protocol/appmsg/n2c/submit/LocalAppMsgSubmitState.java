package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2c.submit;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2c.submit.messages.*;

public enum LocalAppMsgSubmitState implements LocalAppMsgSubmitStateBase {
    Idle {
        @Override
        public State nextState(Message message) {
            if (message instanceof MsgSubmitMessage)
                return Busy;
            else if (message instanceof MsgDone)
                return Done;
            return this;
        }

        @Override
        public boolean hasAgency(boolean isClient) {
            return isClient;
        }
    },
    Busy {
        @Override
        public State nextState(Message message) {
            if (message instanceof MsgAcceptMessage || message instanceof MsgRejectMessage)
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
