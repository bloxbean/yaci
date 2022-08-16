package com.bloxbean.cardano.yaci.core.protocol.chainsync;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.*;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public enum ChainSyncState implements ChainSyncStateBase {
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
        public boolean hasAgency() {
            return true;
        }
    },
    CanAwait {
        @Override
        public State nextState(Message message) {
            if (message instanceof AwaitReply)
                return MustReply;
            else if (message instanceof RollForward)
                return Idle;
            else if (message instanceof Rollbackward)
                return Idle;
            else
                return Idle;
        }

//        @Override
//        public Message handleInbound(byte[] bytes) {
//            Array array = (Array) CborSerializationUtil.deserialize(bytes);
//            int id = ((UnsignedInteger)array.getDataItems().get(0)).getValue().intValue();
//            switch (id) {
//                case 1:
//                    return new AwaitReply();
//                case 2:
//                    return RollForwardSerializer.INSTANCE.deserialize(bytes);
//                case 3:
//                    return RollbackwardSerializer.INSTANCE.deserialize(bytes);
//                default:
//                    throw new RuntimeException(String.format("Invalid msg id: %d", id));
//            }
//        }

        @Override
        public boolean hasAgency() {
            return false;
        }
    },
    MustReply {
        @Override
        public State nextState(Message message) {
            if (message instanceof RollForward)
                return Idle;
            else if(message instanceof Rollbackward)
                return Idle;
            else
                return this;
        }

        @Override
        public boolean hasAgency() {
            return false;
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

//        public Message handleInbound(byte[] bytes) {
//            Message message = IntersectFoundSerializer.INSTANCE.deserialize(bytes);
//            if (message == null)
//                message = IntersectNotFoundSerializer.INSTANCE.deserialize(bytes);
//
//            return message;
//        }

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
