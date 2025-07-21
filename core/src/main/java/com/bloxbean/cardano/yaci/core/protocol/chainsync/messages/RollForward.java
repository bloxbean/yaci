package com.bloxbean.cardano.yaci.core.protocol.chainsync.messages;

import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.byron.ByronBlockHead;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbHead;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.serializers.RollForwardSerializer;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
public class RollForward implements Message {
    private ByronEbHead  byronEbHead;
    private ByronBlockHead byronBlockHead;
    private BlockHeader blockHeader;
    private Tip tip;
    private byte[] originalHeaderBytes; // Store original header bytes for serialization

    // Convenience constructor for backward compatibility
    public RollForward(ByronEbHead byronEbHead, ByronBlockHead byronBlockHead, BlockHeader blockHeader, Tip tip) {
        this.byronEbHead = byronEbHead;
        this.byronBlockHead = byronBlockHead;
        this.blockHeader = blockHeader;
        this.tip = tip;
        this.originalHeaderBytes = null;
    }

    @Override
    public byte[] serialize() {
        return RollForwardSerializer.INSTANCE.serialize(this);
    }

    @Override
    public String toString() {
        return "RollForward{" +
                "byronBlockHead=" + byronBlockHead +
                ", blockHeader=" + blockHeader +
                ", tip=" + tip +
                '}';
    }
}
