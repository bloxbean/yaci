package com.bloxbean.cardano.yaci.core.protocol.txsubmission.serializers;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.client.exception.CborRuntimeException;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.*;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
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
            int label = ((UnsignedInteger) array.getDataItems().get(0)).getValue().intValue();
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
            array.add(new UnsignedInteger(0));
            array.add(requestTxIds.isBlocking() ? SimpleValue.TRUE : SimpleValue.FALSE);
            array.add(new UnsignedInteger(requestTxIds.getAckTxIds()));
            array.add(new UnsignedInteger(requestTxIds.getReqTxIds()));

            if (log.isTraceEnabled()) {
                log.trace(">> Inside RequestTxIdsSerializer serialize() method. " +
                                "Blocking: {}, AckTxIds: {}, ReqTxIds: {}",
                        requestTxIds.isBlocking(), requestTxIds.getAckTxIds(), requestTxIds.getReqTxIds());
            }

            return CborSerializationUtil.serialize(array);
        }

        @Override
        public RequestTxIds deserializeDI(DataItem di) {
            Array array = (Array) di;

            List<DataItem> dataItemList = array.getDataItems();
            int label = ((UnsignedInteger) dataItemList.get(0)).getValue().intValue();
            if (label != 0)
                throw new CborRuntimeException("Parsing error. Invalid label: " + di);

            boolean isBlocking = dataItemList.get(1) == SimpleValue.TRUE;
            short count1 = ((UnsignedInteger) dataItemList.get(2)).getValue().shortValue();
            short count2 = ((UnsignedInteger) dataItemList.get(3)).getValue().shortValue();

            return new RequestTxIds(isBlocking, count1, count2);
        }
    }

    public enum ReplyTxIdsSerializer implements Serializer<ReplyTxIds> {
        INSTANCE;

        @Override
        public byte[] serialize(ReplyTxIds replyTxIds) {

            Array array = new Array();
            array.add(new UnsignedInteger(1));

            var pairs = new Array();
            // Starts the chunking, (see below for break)
            pairs.setChunked(true);

            if (replyTxIds.getTxIdAndSizeMap() != null) {
                replyTxIds.getTxIdAndSizeMap().forEach((txId, size) -> {
                    var pair = new Array();
                    var era = new Array();
                    era.add(new UnsignedInteger(txId.getEra().getValue()));
                    era.add(new ByteString(txId.getTxId()));
                    pair.add(era);
                    pair.add(new UnsignedInteger(size));
                    pairs.add(pair);
                });
            }

            // stops the chunking
            pairs.add(SimpleValue.BREAK);
            array.add(pairs);


            return CborSerializationUtil.serialize(array);
        }

        @Override
        public ReplyTxIds deserializeDI(DataItem di) {
            Array array = (Array) di;
            List<DataItem> dataItemList = array.getDataItems();

            int label = ((UnsignedInteger) dataItemList.get(0)).getValue().intValue();
            if (label != 1)
                throw new CborRuntimeException("Parsing error. Invalid label: " + di);

            Array pairsArray = (Array) dataItemList.get(1);
            List<DataItem> pairs = pairsArray.getDataItems();
            var txIdAndSizeMap = new java.util.HashMap<TxId, Integer>();

            for (DataItem pairDI : pairs) {
                if (pairDI instanceof Special) {
                    break; // Handle the BREAK special case
                }

                Array pair = (Array) pairDI;
                Array eraAndIdArray = (Array) pair.getDataItems().get(0);

                int eraValue = ((UnsignedInteger) eraAndIdArray.getDataItems().get(0)).getValue().intValue(); // Era
                byte[] txBytes = ((ByteString) eraAndIdArray.getDataItems().get(1)).getBytes();
                int size = ((UnsignedInteger) pair.getDataItems().get(1)).getValue().intValue();

                txIdAndSizeMap.put(new TxId(Era.fromInt(eraValue), txBytes), size);
            }

            if (log.isTraceEnabled()) {
                log.trace(">> Inside ReplyTxIdsSerializer deserializeDI() method. " +);
                                "TxIdAndSizeMap size: {}", txIdAndSizeMap.size());
            }
            return new ReplyTxIds(txIdAndSizeMap);
        }
    }

    public enum RequestTxsSerializer implements Serializer<RequestTxs> {
        INSTANCE;

        @Override
        public byte[] serialize(RequestTxs requestTxs) { //Not used
            Array array = new Array();
            array.add(new UnsignedInteger(2));

            Array txIdArray = new Array();
            txIdArray.setChunked(true);

            if (requestTxs.getTxIds() != null) {
                requestTxs.getTxIds().forEach(txId -> {
                    Array txIdArr = new Array();
                    txIdArr.add(new UnsignedInteger(txId.getEra().getValue()));
                    txIdArr.add(new ByteString(txId.getTxId()));
                    txIdArray.add(txIdArr);
                });
            }

            txIdArray.add(Special.BREAK);
            array.add(txIdArray);

            return CborSerializationUtil.serialize(array);
        }

        @Override
        public RequestTxs deserializeDI(DataItem di) {
            Array array = (Array) di;
            List<DataItem> dataItemList = array.getDataItems();

            int label = ((UnsignedInteger) dataItemList.get(0)).getValue().intValue();
            if (label != 2)
                throw new CborRuntimeException("Parsing error. Invalid label: " + di);

            // list of pairs
            Array txIdArray = (Array) dataItemList.get(1);
            List<TxId> txIds = new ArrayList<>();
            for (DataItem txIdDI : txIdArray.getDataItems()) {
                // if we get to the end of the list exit.
                if (txIdDI instanceof Special) {
                    break;
                }
                var pairs = (Array) txIdDI;
                // Era
                int eraValue = ((UnsignedInteger) pairs.getDataItems().get(0)).getValue().intValue();
                byte[] txId = ((ByteString) pairs.getDataItems().get(1)).getBytes();
                txIds.add(new TxId(Era.fromInt(eraValue), txId));
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
            txArray.setChunked(true);

            if (replyTxs.getTxns() != null) {
                replyTxs.getTxns().forEach(tx -> {

                    var transactions = new Array();
                    var txBody = new ByteString(tx.getTx());
                    txBody.setTag(24L);

                    transactions.add(new UnsignedInteger(tx.getEra().getValue())); // Era
                    transactions.add(txBody);

                    txArray.add(transactions);
                });
            }

            txArray.add(SimpleValue.BREAK);
            array.add(txArray);

            return CborSerializationUtil.serialize(array);
        }

        @Override
        public ReplyTxs deserializeDI(DataItem di) { //Not used
            Array array = (Array) di;
            List<DataItem> dataItemList = array.getDataItems();

            int label = ((UnsignedInteger) dataItemList.get(0)).getValue().intValue();
            if (label != 3)
                throw new CborRuntimeException("Parsing error. Invalid label: " + di);

            Array txArray = (Array) dataItemList.get(1);
            List<Tx> txs = new ArrayList<>();
            for (DataItem txDI : txArray.getDataItems()) {
                if (txDI == SimpleValue.BREAK)
                    break;
                Array tx = (Array) txDI;
                // Era
                int eraValue = ((UnsignedInteger) tx.getDataItems().get(0)).getValue().intValue();
                byte[] txBytes = ((ByteString) tx.getDataItems().get(1)).getBytes();
                txs.add(new Tx(Era.fromInt(eraValue), txBytes));
            }

            var replyTxs = new ReplyTxs();
            txs.forEach(replyTxs::addTx);
            return replyTxs;
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

            int key = ((UnsignedInteger) ((Array) di).getDataItems().get(0)).getValue().intValue();
            if (key == 4)
                return new MsgDone();
            else
                throw new CborRuntimeException("Parsing error. Invalid label: " + di);
        }
    }
}
