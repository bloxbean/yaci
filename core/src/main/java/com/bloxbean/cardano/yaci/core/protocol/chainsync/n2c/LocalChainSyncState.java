package com.bloxbean.cardano.yaci.core.protocol.chainsync.n2c;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.*;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public enum LocalChainSyncState implements LocalChainSyncStateBase {
    Idle {
        @Override
        public State nextState(Message message) {
            if (message instanceof RequestNext)
                return CanAwait;
            else if (message instanceof FindIntersect)
                return Intersect;
            else if (message instanceof ChainSyncMsgDone)
                return Done;
            else
                return this;
        }

        @Override
        public boolean hasAgency(boolean isClient) {
            return isClient;
        }
    },
    CanAwait {
        @Override
        public State nextState(Message message) {
            if (message instanceof AwaitReply)
                return MustReply;
            else if (message instanceof LocalRollForward)
                return Idle;
            else if (message instanceof Rollbackward)
                return Idle;
            else
                return Idle;
        }

        @Override
        public boolean hasAgency(boolean isClient) {
            return !isClient;
        }
    },
    MustReply {
        @Override
        public State nextState(Message message) {
            if (message instanceof LocalRollForward)
                return Idle;
            else if (message instanceof Rollbackward)
                return Idle;
            else
                return this;
        }

        @Override
        public boolean hasAgency(boolean isClient) {
            return !isClient;
        }
    },
    Intersect {
        @Override
        public State nextState(Message message) {
            if (message instanceof IntersectFound)
                return Idle;
            else if (message instanceof IntersectNotFound)
                return Idle;
            else
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
