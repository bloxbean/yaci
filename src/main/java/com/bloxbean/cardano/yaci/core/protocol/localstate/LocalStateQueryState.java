package com.bloxbean.cardano.yaci.core.protocol.localstate;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.protocol.localstate.messages.*;

public enum LocalStateQueryState implements LocalStateQueryStateBase{
    Idle{
        @Override
        public State nextState(Message message) {
            if (message instanceof MsgAcquire)
                return Acquiring;
            else if (message instanceof MsgDone)
                return Done;
            else
                return this;
        }

        @Override
        public boolean hasAgency() {
            return true;
        }
    },
    Acquiring {
        @Override
        public State nextState(Message message) {
            if (message instanceof MsgAcquired)
                return Acquired;
            else if (message instanceof MsgFailure)
                return Idle;
            else
                return this;
        }

        @Override
        public boolean hasAgency() {
            return false;
        }
    },
    Acquired {
        @Override
        public State nextState(Message message) {
            if (message instanceof MsgQuery)
                return Querying;
            else if (message instanceof MsgReAcquire)
                return Acquiring;
            else if (message instanceof MsgRelease)
                return Idle;
            else
                return this;
        }

        @Override
        public boolean hasAgency() {
            return true;
        }
    },
    Querying {
        @Override
        public State nextState(Message message) {
            if (message instanceof MsgResult)
                return Acquired;
            else
                return this;
        }

        @Override
        public boolean hasAgency() {
            return false;
        }
    },
    Done {
        @Override
        public State nextState(Message message) {
            return this;
        }

        @Override
        public boolean hasAgency() {
            return false;
        }
    }
}
