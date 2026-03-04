package com.bloxbean.cardano.yaci.core.protocol.localtx.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.localtx.model.TxSubmissionEraMismatchError;
import com.bloxbean.cardano.yaci.core.protocol.localtx.model.TxSubmissionError;
import com.bloxbean.cardano.yaci.core.protocol.localtx.serializers.LocalTxSubmissionSerializers;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.Collections;
import java.util.List;

@Getter
@Builder
@ToString
public class MsgRejectTx implements Message {
    private final String reasonCbor;
    @Builder.Default
    private final List<TxSubmissionError> errors = Collections.emptyList();
    private final TxSubmissionEraMismatchError eraMismatch;

    public MsgRejectTx(String reasonCbor) {
        this(reasonCbor, Collections.emptyList(), null);
    }

    public MsgRejectTx(String reasonCbor, List<TxSubmissionError> errors, TxSubmissionEraMismatchError eraMismatch) {
        this.reasonCbor = reasonCbor;
        this.errors = errors != null ? errors : Collections.emptyList();
        this.eraMismatch = eraMismatch;
    }

    /**
     * Convenience method: returns the first leaf error's message, or era mismatch message,
     * or falls back to raw CBOR hex.
     */
    public String getErrorMessage() {
        if (eraMismatch != null) {
            return eraMismatch.getMessage();
        }
        if (!errors.isEmpty()) {
            List<TxSubmissionError> leaves = errors.get(0).getLeafErrors();
            if (!leaves.isEmpty() && leaves.get(0).getMessage() != null) {
                return leaves.get(0).getMessage();
            }
            if (errors.get(0).getMessage() != null) {
                return errors.get(0).getMessage();
            }
        }
        return reasonCbor;
    }

    @Override
    public byte[] serialize() { //TODO -- Not used as client receives it
        return LocalTxSubmissionSerializers.MsgRejectTxSerializer.INSTANCE.serialize(this);
    }
}
