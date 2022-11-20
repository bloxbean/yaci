package com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class RequestTxs implements Message {
    private List<String> txIds;

    public void addTxnId(String txId) {
        if (txIds == null)
            txIds = new ArrayList<>();

        txIds.add(txId);
    }
}
