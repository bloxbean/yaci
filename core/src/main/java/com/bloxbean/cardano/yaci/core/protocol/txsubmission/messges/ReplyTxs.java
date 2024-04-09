package com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.serializers.TxSubmissionMessagesSerializers;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Getter
@RequiredArgsConstructor
public class ReplyTxs implements Message {

    private final Era era;

    private List<byte[]> txns;

    public ReplyTxs() {
        this(Era.Babbage);
    }

    public void addTx(byte[] tx) {
        if (txns == null)
            txns = new ArrayList<>();

        txns.add(tx);
    }

    @Override
    public byte[] serialize() {
        return TxSubmissionMessagesSerializers.ReplyTxsSerializer.INSTANCE.serialize(this);
    }
}
