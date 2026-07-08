package com.bloxbean.cardano.yaci.core.protocol.appchainsync;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.protocol.appchainsync.messages.*;

/**
 * App Chain Sync mini-protocol (protocol id 103) — catch-up transfer of
 * finalized app blocks, a deliberately simple BlockFetch analog.
 * See cddl/appmsg/app-chain-sync-v103.cddl.
 *
 * Idle (client agency) --MsgRequestRange--> Busy (server agency)
 * Busy --MsgBlocks/MsgNoBlocks--> Idle
 * Idle --MsgDone--> Done
 */
public enum AppChainSyncState implements AppChainSyncStateBase {
    Idle {
        @Override
        public State nextState(Message message) {
            if (message instanceof MsgRequestRange)
                return Busy;
            if (message instanceof MsgDone)
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
            if (message instanceof MsgBlocks || message instanceof MsgNoBlocks)
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
