package com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosRawCbor;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.serializers.LeiosFetchSerializers;

import com.bloxbean.cardano.client.common.cbor.CborSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reply to {@link MsgLeiosBlockTxsRequest}: {@code [4, [* #6.24(bytes .cbor block_transaction)]]}.
 * It does not echo the requested point or bitmap.
 */
public class MsgLeiosBlockTxs implements Message {
    private final LeiosRawCbor txList;

    public MsgLeiosBlockTxs(LeiosRawCbor txList) {
        this.txList = Objects.requireNonNull(txList, "txList");
    }

    public LeiosRawCbor getTxList() {
        return txList;
    }

    /**
     * The transactions as received: the bytes inside each item's {@code #6.24} wrapper, which an endorser block's
     * reference hashes, in list order.
     *
     * @return the transactions' bytes
     * @throws IllegalArgumentException if the list is not an array of byte strings tagged 24
     */
    public List<byte[]> getTransactions() {
        CborSpan list = CborSpan.of(txList.getCbor());
        if (list.majorType() != 4 || list.isTagged())
            throw new IllegalArgumentException("MsgLeiosBlockTxs must carry an array of transactions");
        List<byte[]> transactions = new ArrayList<>(list.size());
        for (CborSpan item : list.items()) {
            if (item.tag() != 24 || item.untag().majorType() != 2 || item.untag().isTagged())
                throw new IllegalArgumentException("A MsgLeiosBlockTxs transaction must be #6.24(bytes)");
            transactions.add(item.untag().byteString());
        }
        return transactions;
    }

    @Override
    public byte[] serialize() {
        return LeiosFetchSerializers.MsgLeiosBlockTxsSerializer.INSTANCE.serialize(this);
    }
}
