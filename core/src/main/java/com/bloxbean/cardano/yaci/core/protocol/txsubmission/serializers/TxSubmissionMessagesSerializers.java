package com.bloxbean.cardano.yaci.core.protocol.txsubmission.serializers;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.client.exception.CborRuntimeException;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.*;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.util.ArrayList;
import java.util.List;

public class TxSubmissionMessagesSerializers {

    public enum InitSerializer implements Serializer<Init> {
            INSTANCE;

        @Override
        public byte[] serialize(Init object) {
            Array array = new Array();
            array.add(new UnsignedInteger(6));

            return CborSerializationUtil.serialize(array);
        }

        @Override
        public Init deserializeDI(DataItem di) { //Not used
            Array array = (Array) di;
            int label = ((UnsignedInteger)array.getDataItems().get(0)).getValue().intValue();
            if (label == 6)
                return new Init();
            else
                throw new CborRuntimeException("Invalid message : " + di);
        }
    }

    public enum RequestTxIdsSerializer implements Serializer<RequestTxIds> {
        INSTANCE;

        @Override
        public byte[] serialize(RequestTxIds requestTxIds) { //Not Used
            Array array = new Array();
            array.add(requestTxIds.isBlocking()? SimpleValue.TRUE: SimpleValue.FALSE);
            array.add(new UnsignedInteger(requestTxIds.getAckTxIds()));
            array.add(new UnsignedInteger(requestTxIds.getReqTxIds()));

            return CborSerializationUtil.serialize(array);
        }

        @Override
        public RequestTxIds deserializeDI(DataItem di) {
            Array array = (Array) di;

            List<DataItem> dataItemList = array.getDataItems();
            int label = ((UnsignedInteger)dataItemList.get(0)).getValue().intValue();
            if (label != 0)
                throw new CborRuntimeException("Parsing error. Invalid label: " + di);

            boolean isBlocking = dataItemList.get(1) == SimpleValue.TRUE;
            short count1 = ((UnsignedInteger)dataItemList.get(2)).getValue().shortValue();
            short count2 = ((UnsignedInteger)dataItemList.get(3)).getValue().shortValue();

            return new RequestTxIds(isBlocking, count1, count2);
        }
    }

    public enum ReplyTxIdsSerializer implements Serializer<ReplyTxIds> {
        INSTANCE;

        @Override
        public byte[] serialize(ReplyTxIds replyTxIds) {
            Array array = new Array();
            array.add(new UnsignedInteger(1));

//            Map map = new Map();
//            if (replyTxIds.getTxIdAndSizeMap() != null) {
//                replyTxIds.getTxIdAndSizeMap().forEach((id, size) -> {
//                      map.put(new ByteString(HexUtil.decodeHexString(id)), new UnsignedInteger(size));
//                });
//            }
//
//            array.add(map);

            var pairs = new Array();

            replyTxIds.getTxIdAndSizeMap().forEach((id, size) -> {
                var pair = new Array();
                pair.add(new ByteString(HexUtil.decodeHexString(id)));
                pair.add(new UnsignedInteger(size));
                pairs.add(pair);
            });

            array.add(pairs);


            return CborSerializationUtil.serialize(array);
        }

        //TODO deserializeDI() -- Not used
    }

    public enum RequestTxsSerializer implements Serializer<RequestTxs> {
        INSTANCE;

        @Override
        public byte[] serialize(RequestTxs requestTxs) { //Not used
            Array array = new Array();
            array.add(new UnsignedInteger(2));

            Array txIdArray = new Array();
            if (requestTxs.getTxIds() != null) {
                requestTxs.getTxIds().forEach(txId -> txIdArray.add(new ByteString(HexUtil.decodeHexString(txId))) );
            }

            array.add(txIdArray);

            return CborSerializationUtil.serialize(array);
        }

        @Override
        public RequestTxs deserializeDI(DataItem di) {
            Array array = (Array) di;
            List<DataItem> dataItemList = array.getDataItems();

            int label = ((UnsignedInteger)dataItemList.get(0)).getValue().intValue();
            if (label != 2)
                throw new CborRuntimeException("Parsing error. Invalid label: " + di);

            Array txIdArray = (Array) dataItemList.get(1);
            List<String> txIds = new ArrayList<>();
            for (DataItem txIdDI: txIdArray.getDataItems()) {
                String txId = HexUtil.encodeHexString(((ByteString)txIdDI).getBytes());
                txIds.add(txId);
            }

            return new RequestTxs(txIds);
        }
    }

    public enum ReplyTxsSerializer implements Serializer<ReplyTxs> {
        INSTANCE;

        @Override
        public byte[] serialize(ReplyTxs replyTxs) {
            Array array = new Array();
            array.add(new UnsignedInteger(3));

            Array txArray = new Array();
            if (replyTxs.getTxns() != null) {
                replyTxs.getTxns().forEach(tx -> txArray.add(new ByteString(tx)) );
            }

            array.add(txArray);

            return CborSerializationUtil.serialize(array);
        }

        @Override
        public ReplyTxs deserializeDI(DataItem di) { //Not used
            Array array = (Array) di;
            List<DataItem> dataItemList = array.getDataItems();

            int label = ((UnsignedInteger)dataItemList.get(0)).getValue().intValue();
            if (label != 3)
                throw new CborRuntimeException("Parsing error. Invalid label: " + di);

            Array txArray = (Array) dataItemList.get(1);
            List<byte[]> txs = new ArrayList<>();
            for (DataItem txDI: txArray.getDataItems()) {
                byte[] tx = ((ByteString)txDI).getBytes();
                txs.add(tx);
            }

            return new ReplyTxs(txs);
        }
    }

    public enum MsgDoneSerializer implements Serializer<MsgDone> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgDone object) {
            Array array = new Array();
            array.add(new UnsignedInteger(4));

            return CborSerializationUtil.serialize(array);
        }

        @Override
        public MsgDone deserialize(byte[] bytes) {
            DataItem di = CborSerializationUtil.deserializeOne(bytes);

            int key = ((UnsignedInteger)((Array) di).getDataItems().get(0)).getValue().intValue();
            if (key == 4)
                return new MsgDone();
            else
                throw new CborRuntimeException("Parsing error. Invalid label: " + di);
        }
    }
}
