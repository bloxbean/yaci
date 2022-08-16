package com.bloxbean.cardano.yaci.core.protocol.blockfetch;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.messages.*;

public enum BlockfetchState implements BlockfetchStateBase {
    Idle {
        @Override
        public State nextState(Message message) {
            if (message instanceof RequestRange)
                return Busy;
            else if (message instanceof ClientDone)
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
            if (message instanceof NoBlocks)
                return Idle;
            else if (message instanceof StartBatch)
                return Streaming;
            else
                return this;
        }

        @Override
        public boolean hasAgency() {
            return false;
        }
    },
    Streaming {
        @Override
        public State nextState(Message message) {
            if (message instanceof MsgBlock) {
                return Streaming;
            } else if (message instanceof BatchDone)
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
