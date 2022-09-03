package com.bloxbean.cardano.yaci.core.protocol.localtx.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class MsgSubmitTx implements Message {
    private byte[] txnBytes;
}
