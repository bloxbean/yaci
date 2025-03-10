package com.bloxbean.cardano.yaci.core.protocol.localtxmonitor;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.protocol.localtxmonitor.messages.*;

import java.util.List;

public enum LocalTxMonitorState implements LocalTxMonitorStateBase {
    Idle {
        @Override
        public State nextState(Message message) {
            if (message instanceof MsgAwaitAcquire || message instanceof MsgAcquire)
                return Acquiring;
            else if (message instanceof MsgDone)
                return Done;
            else
                return this;
        }

        @Override
        public boolean hasAgency(boolean isClient) {
            return isClient;
        }

        List<Class> allowedMsgTypes = List.of(MsgAwaitAcquire.class, MsgAcquire.class, MsgDone.class);

        @Override
        public List<Class> allowedMessageTypes() {
            return allowedMsgTypes;
        }
    },
    Acquiring {
        @Override
        public State nextState(Message message) {
            if (message instanceof MsgAcquired)
                return Acquired;
            else
                return this;
        }

        @Override
        public boolean hasAgency(boolean isClient) {
            return !isClient;
        }

        List<Class> allowedMsgTypes = List.of(MsgAcquired.class);

        @Override
        public List<Class> allowedMessageTypes() {
            return allowedMsgTypes;
        }
    },
    Acquired {
        @Override
        public State nextState(Message message) {
            if (message instanceof MsgAwaitAcquire)
                return Acquiring;
            else if (message instanceof MsgRelease)
                return Idle;
            else if (message instanceof MsgHasTx || message instanceof MsgNextTx || message instanceof MsgGetSizes)
                return Busy;
            else
                return this;
        }

        @Override
        public boolean hasAgency(boolean isClient) {
            return isClient;
        }

        List<Class> allowedMsgTypes = List.of(MsgAwaitAcquire.class, MsgRelease.class, MsgHasTx.class, MsgNextTx.class, MsgGetSizes.class);

        @Override
        public List<Class> allowedMessageTypes() {
            return allowedMsgTypes;
        }
    },
    Busy {
        @Override
        public State nextState(Message message) {
            if (message instanceof MsgReplyHasTx || message instanceof MsgReplyNextTx || message instanceof MsgReplyGetSizes)
                return Acquired;
            else
                return this;
        }

        @Override
        public boolean hasAgency(boolean isClient) {
            return !isClient;
        }

        List<Class> allowedMsgTypes = List.of(MsgReplyHasTx.class, MsgReplyNextTx.class, MsgReplyGetSizes.class);

        @Override
        public List<Class> allowedMessageTypes() {
            return allowedMsgTypes;
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
