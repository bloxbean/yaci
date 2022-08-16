package com.bloxbean.cardano.yaci.core.protocol.chainsync.messages;

import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
public class RollForward implements Message {
    private BlockHeader blockHeader;
    private Tip tip;

    @Override
    public String toString() {
        return "RollForward{" +
                "blockHeader=" + blockHeader +
                ", tip=" + tip +
                '}';
    }
}
