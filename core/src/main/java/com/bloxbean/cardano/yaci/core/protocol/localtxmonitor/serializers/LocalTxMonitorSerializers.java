package com.bloxbean.cardano.yaci.core.protocol.localtxmonitor.serializers;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.localtxmonitor.messages.*;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class LocalTxMonitorSerializers {
    //cddl
    /**
     * LocalTxMonitorMessage
     * = msgDone
     * / msgAcquire
     * / msgAcquired
     * / msgNextTx
     * / msgReplyNextTx
     * / msgHasTx
     * / msgReplyHasTx
     * / msgGetSizes
     * / msgReplyGetSizes
     * / msgRelease
     * <p>
     * msgDone          = [0]
     * <p>
     * msgAcquire       = [1]
     * msgAcquired      = [2, slotNo]
     * <p>
     * msgAwaitAcquire  = msgAcquire
     * msgRelease       = [3]
     * msgNextTx        = [5]
     * msgReplyNextTx   = [6] / [6, transaction]
     * msgHasTx         = [7, txId]
     * msgReplyHasTx    = [8, bool]
     * msgGetSizes      = [9]
     * msgReplyGetSizes = [10, [word32, word32, word32]]
     **/

    public enum MsgDoneSerializer implements Serializer<MsgDone> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgDone object) {
            Array array = new Array();
            array.add(new UnsignedInteger(0));

            if (log.isDebugEnabled())
                log.debug("MsgDone (serialized): {}",
                        HexUtil.encodeHexString(CborSerializationUtil.serialize(array)));

            return CborSerializationUtil.serialize(array);
        }
    }

    public enum MsgAcquireSerializer implements Serializer<MsgAcquire> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgAcquire object) {
            Array array = new Array();
            array.add(new UnsignedInteger(1));

            if (log.isDebugEnabled())
                log.debug("MsgAcquire (serialized): {}",
                        HexUtil.encodeHexString(CborSerializationUtil.serialize(array)));

            return CborSerializationUtil.serialize(array);
        }
    }

    public enum MsgAcquiredSerializer implements Serializer<MsgAcquired> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgAcquired msgAcquired) {
            Array array = new Array();
            array.add(new UnsignedInteger(2));
            array.add(new UnsignedInteger(msgAcquired.getSlotNo()));

            if (log.isDebugEnabled())
                log.debug("MsgAcquired (serialized): {}",
                        HexUtil.encodeHexString(CborSerializationUtil.serialize(array)));

            return CborSerializationUtil.serialize(array);
        }

        @Override
        public MsgAcquired deserializeDI(DataItem di) {
            List<DataItem> dataItemList = ((Array)di).getDataItems();
            int key = ((UnsignedInteger)dataItemList.get(0)).getValue().intValue();
            if (key != 2)
                throw new IllegalStateException("Invalid key. Expected : " + 2 + " Found: " + key);

            long slotNo = ((UnsignedInteger)dataItemList.get(1)).getValue().longValue();

            return new MsgAcquired(slotNo);
        }
    }

    public enum MsgAwaitAcquireSerializer implements Serializer<MsgAwaitAcquire> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgAwaitAcquire object) {
            return MsgAcquireSerializer.INSTANCE.serialize(new MsgAcquire());
        }
    }

    public enum MsgReleaseSerializer implements Serializer<MsgRelease> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgRelease object) {
            Array array = new Array();
            array.add(new UnsignedInteger(3));

            if (log.isDebugEnabled())
                log.debug("MsgRelease (serialized): {}",
                        HexUtil.encodeHexString(CborSerializationUtil.serialize(array)));

            return CborSerializationUtil.serialize(array);
        }
    }

    public enum MsgNextTxSerializer implements Serializer<MsgNextTx> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgNextTx object) {
            Array array = new Array();
            array.add(new UnsignedInteger(5));

            if (log.isDebugEnabled())
                log.debug("MsgNextTx (serialized): {}",
                        HexUtil.encodeHexString(CborSerializationUtil.serialize(array)));

            return CborSerializationUtil.serialize(array);
        }
    }

    public enum MsgReplyNextTxSerializer implements Serializer<MsgReplyNextTx> {
        INSTANCE;

        @Override
        public MsgReplyNextTx deserializeDI(DataItem di) {
            List<DataItem> dataItemList = ((Array)di).getDataItems();
            int key = ((UnsignedInteger)dataItemList.get(0)).getValue().intValue();
            if (key != 6)
                throw new IllegalStateException("Invalid key. Expected : " + 6 + " Found: " + key);

            if (dataItemList.size() == 2) {
                List<DataItem> txDataItems = ((Array)dataItemList.get(1)).getDataItems();
                int era = ((UnsignedInteger)txDataItems.get(0)).getValue().intValue();
                byte[] transaction = ((ByteString)txDataItems.get(1)).getBytes();
                return new MsgReplyNextTx(era, transaction);
            } else
                return new MsgReplyNextTx();
        }
    }

    public enum MsgHasTxSerializer implements Serializer<MsgHasTx> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgHasTx msgHasTx) {
            Array array = new Array();
            array.add(new UnsignedInteger(7));
            //array.add(new UnicodeString(msgHasTx.getTxnId()));
            array.add(new ByteString(HexUtil.decodeHexString(msgHasTx.getTxnId())));

            if (log.isDebugEnabled())
                log.debug("MsgHasTx (serialized): {}",
                        HexUtil.encodeHexString(CborSerializationUtil.serialize(array)));

            return CborSerializationUtil.serialize(array);
        }
    }

    public enum MsgReplyHasTxSerializer implements Serializer<MsgReplyHasTx> {
        INSTANCE;

        @Override
        public MsgReplyHasTx deserializeDI(DataItem di) {
            List<DataItem> dataItemList = ((Array)di).getDataItems();
            int key = ((UnsignedInteger)dataItemList.get(0)).getValue().intValue();
            if (key != 8)
                throw new IllegalStateException("Invalid key. Expected : " + 8 + " Found: " + key);

            if (dataItemList.size() != 2)
                throw new IllegalStateException("Invalid length. Expected : " + 2 + " Found: " + dataItemList.size());

            SimpleValue simpleValue = ((SimpleValue)dataItemList.get(1));
            if (SimpleValue.TRUE.equals(simpleValue))
                return new MsgReplyHasTx(true);
            else
                return new MsgReplyHasTx(false);
        }
    }

    public enum MsgGetSizesSerializer implements Serializer<MsgGetSizes> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgGetSizes msgGetSizes) {
            Array array = new Array();
            array.add(new UnsignedInteger(9));

            if (log.isDebugEnabled())
                log.debug("MsgGetSizes (serialized): {}",
                        HexUtil.encodeHexString(CborSerializationUtil.serialize(array)));

            return CborSerializationUtil.serialize(array);
        }
    }

    public enum MsgReplyGetSizesSerializer implements Serializer<MsgReplyGetSizes> {
        INSTANCE;

        @Override
        public MsgReplyGetSizes deserializeDI(DataItem di) {
            List<DataItem> dataItemList = ((Array)di).getDataItems();
            int key = ((UnsignedInteger)dataItemList.get(0)).getValue().intValue();
            if (key != 10)
                throw new IllegalStateException("Invalid key. Expected : " + 10 + " Found: " + key);

            if (dataItemList.size() != 2)
                throw new IllegalStateException("Invalid length. Expected : " + 2 + " Found: " + dataItemList.size());

            List<DataItem> sizesDIList = ((Array)dataItemList.get(1)).getDataItems();
            int capacityInBytes = ((UnsignedInteger)sizesDIList.get(0)).getValue().intValue();
            int sizeInBytes = ((UnsignedInteger)sizesDIList.get(1)).getValue().intValue();
            int numberOfTxs = ((UnsignedInteger)sizesDIList.get(2)).getValue().intValue();

            return new MsgReplyGetSizes(capacityInBytes, sizeInBytes, numberOfTxs);
        }
    }

}
