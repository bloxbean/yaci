package com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.serializers.TxSubmissionMessagesSerializers;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

@Getter
public class ReplyTxs implements Message {
    private List<Tx> txns;

    public ReplyTxs() {

    }

    public void addTx(Tx tx) {
        if (txns == null)
            txns = new ArrayList<>();

        txns.add(tx);
    }

    @Override
    public byte[] serialize() {
        return TxSubmissionMessagesSerializers.ReplyTxsSerializer.INSTANCE.serialize(this);
    }
}
