package com.bloxbean.cardano.yaci.core.model.serializers.leios;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.leios.EndorserBlockTx;
import com.bloxbean.cardano.yaci.core.protocol.leios.serializers.LeiosCborUtil;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Decodes Leios EB transaction-list responses while preserving each tag-24 full transaction byte string.
 */
public enum EndorserBlockTxListSerializer implements Serializer<List<EndorserBlockTx>> {
    INSTANCE;

    @Override
    public List<EndorserBlockTx> deserialize(byte[] bytes) {
        LeiosCborReader reader = new LeiosCborReader(bytes);
        long arraySize = reader.readLength(LeiosCborReader.MAJOR_TYPE_ARRAY);
        List<EndorserBlockTx> transactions = new ArrayList<>();
        int index = 0;
        if (arraySize == LeiosCborReader.INDEFINITE) {
            while (!reader.nextIsBreak()) {
                transactions.add(readTx(index++, reader.readDataItem()));
            }
            reader.readBreak();
        } else {
            for (long i = 0; i < arraySize; i++) {
                transactions.add(readTx(index++, reader.readDataItem()));
            }
        }
        reader.requireEnd();
        return transactions;
    }

    @Override
    public List<EndorserBlockTx> deserializeDI(DataItem di) {
        return deserialize(CborSerializationUtil.serialize(di, false));
    }

    private EndorserBlockTx readTx(int index, LeiosCborReader.DecodedItem item) {
        String itemCbor = HexUtil.encodeHexString(item.rawBytes());
        try {
            if (!(item.dataItem() instanceof Array txArray)) {
                return rawOnly(index, itemCbor);
            }

            List<DataItem> items = LeiosCborUtil.arrayItems(txArray, "Endorser Block tx");
            if (items.size() != 2 || !(items.get(0) instanceof UnsignedInteger eraItem)
                    || !(items.get(1) instanceof ByteString txBytesItem)
                    || !LeiosCborUtil.hasTag(txBytesItem, 24)) {
                return rawOnly(index, itemCbor);
            }

            int txEraIndex = LeiosCborUtil.toInt(eraItem, "tx era index");
            Era era = LeiosEraUtil.fromTxEraIndex(txEraIndex);
            byte[] txBytes = txBytesItem.getBytes();
            return EndorserBlockTx.builder()
                    .index(index)
                    .txEraIndex(txEraIndex)
                    .era(era)
                    .txCbor(HexUtil.encodeHexString(txBytes))
                    .txHash(HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(txBytes)))
                    .cbor(itemCbor)
                    .parsed(era != null)
                    .build();
        } catch (Exception e) {
            return rawOnly(index, itemCbor);
        }
    }

    private EndorserBlockTx rawOnly(int index, String itemCbor) {
        return EndorserBlockTx.builder()
                .index(index)
                .txEraIndex(-1)
                .cbor(itemCbor)
                .parsed(false)
                .build();
    }
}
