package com.bloxbean.cardano.yaci.core.protocol.localtx.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.localtx.model.TxSubmissionError;
import com.bloxbean.cardano.yaci.core.protocol.localtx.serializers.LocalTxSubmissionSerializers;
import com.bloxbean.cardano.yaci.core.protocol.localtx.serializers.TxRejectionDecoder;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

@Getter
@ToString
public class MsgRejectTx implements Message {
    private String reasonCbor;
    private transient TxSubmissionError parsedError;
    private transient TxRejectionDecoder decoder = new TxRejectionDecoder();
    
    public MsgRejectTx(String reasonCbor) {
        this.reasonCbor = reasonCbor;
    }
    
    public MsgRejectTx(String reasonCbor, TxSubmissionError parsedError, TxRejectionDecoder decoder) {
        this.reasonCbor = reasonCbor;
        this.parsedError = parsedError;
        this.decoder = decoder != null ? decoder : new TxRejectionDecoder();
    }

    /**
     * Get the parsed error details. Parsing is done lazily on first access.
     * @return The parsed error or an unparsed error with the original CBOR hex
     */
    public TxSubmissionError getParsedError() {
        if (parsedError == null) {
            parsedError = decoder.decode(reasonCbor);
        }
        return parsedError;
    }
    
    /**
     * Get a user-friendly error message
     * @return A human-readable error message or the CBOR hex as fallback
     */
    public String getUserFriendlyMessage() {
        TxSubmissionError error = getParsedError();
        if (error != null) {
            return error.getDisplayMessage();
        }
        return "Transaction rejected with error: " + reasonCbor;
    }

    @Override
    public byte[] serialize() { //TODO -- Not used as client receives it
        return LocalTxSubmissionSerializers.MsgRejectTxSerializer.INSTANCE.serialize(this);
    }

}
