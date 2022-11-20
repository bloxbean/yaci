package com.bloxbean.cardano.yaci.core.protocol.localtx;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.protocol.localtx.messages.MsgAcceptTx;
import com.bloxbean.cardano.yaci.core.protocol.localtx.messages.MsgDone;
import com.bloxbean.cardano.yaci.core.protocol.localtx.messages.MsgRejectTx;
import com.bloxbean.cardano.yaci.core.protocol.localtx.messages.MsgSubmitTx;

public enum LocalTxSubmissionState implements LocalTxSubmissionStateBase {
    Idle {
        @Override
        public State nextState(Message message) {
            if (message instanceof MsgSubmitTx)
                return Busy;
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
    Busy {
        @Override
        public State nextState(Message message) {
            if (message instanceof MsgAcceptTx || message instanceof MsgRejectTx)
                return Idle;
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
