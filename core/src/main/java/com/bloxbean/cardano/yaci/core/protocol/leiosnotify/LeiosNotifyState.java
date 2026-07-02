package com.bloxbean.cardano.yaci.core.protocol.leiosnotify;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgClientDone;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosBlockAnnouncement;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosBlockOffer;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosBlockTxsOffer;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosNotificationRequestNext;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosVotes;

public enum LeiosNotifyState implements LeiosNotifyStateBase {
    StIdle {
        @Override
        public State nextState(Message message) {
            if (message instanceof MsgLeiosNotificationRequestNext) {
                return StBusy;
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
    StBusy {
        @Override
        public State nextState(Message message) {
            if (message instanceof MsgLeiosBlockAnnouncement ||
                    message instanceof MsgLeiosBlockOffer ||
                    message instanceof MsgLeiosBlockTxsOffer ||
                    message instanceof MsgLeiosVotes) {
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
