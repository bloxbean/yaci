package com.bloxbean.cardano.yaci.core.protocol.chainsync.messages;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
public class LocalRollForward implements Message {
    private ByronEbBlock byronEbBlock;
    private ByronMainBlock byronBlock;
    private Block block;
    private Tip tip;

    @Override
    public String toString() {
        return "RollForward{" +
                "block=" + block +
                ", tip=" + tip +
                '}';
    }
}
