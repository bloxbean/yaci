package com.bloxbean.cardano.yaci.core.protocol.txsubmission;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.ReplyTxIds;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.ReplyTxs;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.RequestTxIds;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.RequestTxs;

public enum TxSubmissionState implements TxSubmissionStateBase {
    Init {
        @Override
        public State nextState(Message message) {
            if (message instanceof com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.Init) {
                return Idle;
            } else
                return this;
        }

        @Override
        public boolean hasAgency() {
            return true;
        }
    },
    Idle {
        @Override
        public State nextState(Message message) {
            if (message instanceof RequestTxIds) {
                RequestTxIds requestTxIds = (RequestTxIds) message;
                return requestTxIds.isBlocking()? TxIdsBlocking: TxIdsNonBlocking;
            } else if (message instanceof RequestTxs)
                return Txs;
            else
                return this;
        }

        @Override
        public boolean hasAgency() {
            return false;
        }
    },
    TxIdsBlocking {
        @Override
        public State nextState(Message message) {
            if (message instanceof ReplyTxIds)
                return Idle;
            else
                return this;
        }

        @Override
        public boolean hasAgency() {
            return true;
        }
    },
    TxIdsNonBlocking {
        @Override
        public State nextState(Message message) {
            if (message instanceof ReplyTxIds)
                return Idle;
            else
                return this;
        }

        @Override
        public boolean hasAgency() {
            return true;
        }
    },
    Txs {
        @Override
        public State nextState(Message message) {
            if (message instanceof ReplyTxs)
                return Idle;
            else
                return this;
        }

        @Override
        public boolean hasAgency() {
            return true;
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
