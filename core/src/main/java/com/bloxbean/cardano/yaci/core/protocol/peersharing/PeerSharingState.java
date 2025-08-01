package com.bloxbean.cardano.yaci.core.protocol.peersharing;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.peersharing.messages.MsgShareRequest;
import com.bloxbean.cardano.yaci.core.protocol.peersharing.messages.MsgSharePeers;
import com.bloxbean.cardano.yaci.core.protocol.peersharing.messages.MsgDone;

public enum PeerSharingState implements PeerSharingStateBase {
    StIdle {
        @Override
        public PeerSharingState nextState(Message message) {
            if (message instanceof MsgShareRequest) {
                return StBusy;
            } else if (message instanceof MsgDone) {
                return StDone;
            }
            return this;
        }

        @Override
        public boolean hasAgency() {
            return true; // Client has agency in idle state
        }
    },

    StBusy {
        @Override
        public PeerSharingState nextState(Message message) {
            if (message instanceof MsgSharePeers) {
                return StIdle;
            } else if (message instanceof MsgDone) {
                return StDone;
            } else if (message == null) {
                log.debug("Received null message in busy state, remaining in busy state");
                return this;
            }
            return this;
        }

        @Override
        public boolean hasAgency() {
            return false; // Server has agency in busy state
        }
    },

    StDone {
        @Override
        public PeerSharingState nextState(Message message) {
            return this; // Terminal state
        }

        @Override
        public boolean hasAgency() {
            return false; // No agency in terminal state
        }
    }
}
