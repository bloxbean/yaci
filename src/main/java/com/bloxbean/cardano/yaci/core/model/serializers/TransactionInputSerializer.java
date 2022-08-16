package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.exception.CborRuntimeException;
import com.bloxbean.cardano.yaci.core.model.TransactionInput;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.util.List;

public enum TransactionInputSerializer implements Serializer<TransactionInput> {
    INSTANCE;

    @Override
    public TransactionInput deserializeDI(DataItem di) {
        Array inputItems = (Array)di;
        List<DataItem> items = inputItems.getDataItems();

        if(items == null || items.size() != 2) {
            throw new CborRuntimeException("TransactionInput deserialization failed. Invalid no of DataItems");
        }

        TransactionInput.TransactionInputBuilder transactionInput = TransactionInput.builder();

        ByteString txnIdBytes = (ByteString) items.get(0);
        if(txnIdBytes != null)
            transactionInput.transactionId(HexUtil.encodeHexString(txnIdBytes.getBytes()));

        UnsignedInteger indexUI = (UnsignedInteger) items.get(1);
        if(indexUI != null)
            transactionInput.index(indexUI.getValue().intValue());

        return transactionInput.build();
    }

}
