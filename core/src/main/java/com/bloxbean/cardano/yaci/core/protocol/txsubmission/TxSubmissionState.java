package com.bloxbean.cardano.yaci.core.protocol.txsubmission;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.MsgDone;
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
        public boolean hasAgency(boolean isClient) {
            return isClient;
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
        public boolean hasAgency(boolean isClient) {
            return !isClient;
        }
    },
    TxIdsBlocking {
        @Override
        public State nextState(Message message) {
            // Per the node-to-node tx-submission mini-protocol, the client may terminate the
            // protocol with MsgDone instead of ReplyTxIds while the server is blocking-waiting.
            // Transition to Done so the agent can be torn down and re-created on reconnect;
            // without this the server agent stays wedged here forever (client holds agency, so
            // it can never send another RequestTxIds) and stops ingesting mempool txs.
            if (message instanceof ReplyTxIds)
                return Idle;
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
    TxIdsNonBlocking {
        @Override
        public State nextState(Message message) {
            if (message instanceof ReplyTxIds)
                return Idle;
            else if (message instanceof MsgDone) // defensive: honour client termination
                return Done;
            else
                return this;
        }

        @Override
        public boolean hasAgency(boolean isClient) {
            return isClient;
        }
    },
    Txs {
        @Override
        public State nextState(Message message) {
            if (message instanceof ReplyTxs)
                return Idle;
            else if (message instanceof MsgDone) // defensive: honour client termination
                return Done;
            else
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
