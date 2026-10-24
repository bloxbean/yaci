package com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.serializers.TxSubmissionMessagesSerializers;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

@Getter
public class ReplyTxs implements Message {
    // Never null: a reply may hold no transactions, when the client no longer has any of those requested
    private List<Tx> txns = new ArrayList<>();

    public ReplyTxs() {

    }

    public void addTx(Tx tx) {
        txns.add(tx);
    }

    @Override
    public byte[] serialize() {
        return TxSubmissionMessagesSerializers.ReplyTxsSerializer.INSTANCE.serialize(this);
    }
}
