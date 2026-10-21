package com.bloxbean.cardano.yaci.core.protocol.leiosnotify;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgCanceled;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgDone;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosBlockAnnouncement;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosBlockOffer;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosBlockTxsOffer;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosNotificationRequestNext;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosVotes;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgQuit;

public enum LeiosNotifyState implements LeiosNotifyStateBase {
    StIdle {
        @Override
        public State nextState(Message message) {
            if (message instanceof MsgLeiosNotificationRequestNext) {
                return StBusy;
            }
            if (message instanceof MsgQuit) {
                return StQuit;
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
                    message instanceof MsgLeiosVotes ||
                    message instanceof MsgCanceled) {
                return StIdle;
            }
            return this;
        }

        @Override
        public boolean hasAgency(boolean isClient) {
            return !isClient;
        }
    },
    StQuit {
        @Override
        public State nextState(Message message) {
            if (message instanceof MsgDone) {
                return StDone;
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
