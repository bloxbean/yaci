package com.bloxbean.cardano.yaci.core.protocol.leiosfetch;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgClientDone;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosBlock;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosBlockRequest;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosBlockTxs;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosBlockTxsRequest;

public enum LeiosFetchState implements LeiosFetchStateBase {
    StIdle {
        @Override
        public State nextState(Message message) {
            if (message instanceof MsgLeiosBlockRequest) {
                return StBlock;
            }
            if (message instanceof MsgLeiosBlockTxsRequest) {
                return StBlockTxs;
            }
            if (message instanceof MsgClientDone) {
                return StDone;
            }
            return this;
        }

        @Override
        public boolean hasAgency(boolean isClient) {
            return isClient;
        }
    },
    StBlock {
        @Override
        public State nextState(Message message) {
            if (message instanceof MsgLeiosBlock) {
                return StIdle;
            }
            return this;
        }

        @Override
        public boolean hasAgency(boolean isClient) {
            return !isClient;
        }
    },
    StBlockTxs {
        @Override
        public State nextState(Message message) {
            if (message instanceof MsgLeiosBlockTxs) {
                return StIdle;
            }
            return this;
        }

        @Override
        public boolean hasAgency(boolean isClient) {
            return !isClient;
        }
    },
    StDone {
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
