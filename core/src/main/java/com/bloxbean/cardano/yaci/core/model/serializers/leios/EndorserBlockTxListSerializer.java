package com.bloxbean.cardano.yaci.core.model.serializers.leios;

import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.leios.EndorserBlockTx;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Decodes the LeiosFetch {@code MsgLeiosBlockTxs} tx list, keeping each item's bytes: Musashi rc-2026w42
 * {@code [* #6.24(bytes .cbor block_transaction)]}, w40a {@code [* bytes]} (mempool encoding).
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

    /**
     * Each transaction is a byte string holding the transaction CBOR: tagged 24 and in block encoding
     * {@code [body, witnesses, aux / nil, is_valid]} from w42, plain and in mempool encoding in w40a. The endorser
     * block references it by blake2b-256 of those inner bytes (the Haskell {@code hashLeiosTx}) and their length.
     */
    private EndorserBlockTx readTx(int index, LeiosCborReader.DecodedItem item) {
        String itemCbor = HexUtil.encodeHexString(item.rawBytes());
        if (!(item.dataItem() instanceof ByteString txBytesItem)) {
            return rawOnly(index, itemCbor);
        }
        byte[] txBytes = txBytesItem.getBytes();
        return EndorserBlockTx.builder()
                .index(index)
                .era(Era.Dijkstra)
                .txCbor(HexUtil.encodeHexString(txBytes))
                .txHash(HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(txBytes)))
                .cbor(itemCbor)
                .parsed(true)
                .build();
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
