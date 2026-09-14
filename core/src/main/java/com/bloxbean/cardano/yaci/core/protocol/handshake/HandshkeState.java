package com.bloxbean.cardano.yaci.core.protocol.handshake;

import com.bloxbean.cardano.yaci.core.protocol.Message;

public enum HandshkeState implements HandshakeStateBase {
    Propose {
        @Override
        public HandshkeState nextState(Message message) {
            return Confirm;
        }

        @Override
        public boolean hasAgency(boolean isClient) {
            return isClient;
        }
    },
    Confirm {
        @Override
        public HandshkeState nextState(Message message) {
            return Done;
        }

        @Override
        public boolean hasAgency(boolean isClient) {
            return !isClient;
        }
    },
    Done {
        @Override
        public HandshkeState nextState(Message message) {
            return this;
        }

        @Override
        public boolean hasAgency(boolean isClient) {
            return false;
        }
    }

}

//public interface State {
//    public abstract State nextState();
//    public abstract boolean hasAgency();
//}
//
////class Propose1 implements State {
////        private Message message;
////        public Propose(ProposedVersions proposedVersions) {
////            this.message = proposedVersions;
////        }
////
////        @Override
////        public State nextState() {
////            return new Confirm(null);
////        }
////
////        @Override
////        public boolean hasAgency() {
////            return true;
////        }
////}
////
////class Confirm1 implements State {
////        private Message message;
////        public Confirm(Message message) {
////            this.message = message;
////        }
////
////        @Override
////        public State nextState() {
////            return new Done();
////        }
////
////        @Override
////        public boolean hasAgency() {
////            return false;
////        }
////}
////
//// class Done1 implements State {
////        @Override
////        public State nextState() {
////            return this;
////        }
////
////        @Override
////        public boolean hasAgency() {
////            return false;
////        }
////    };
//
//
