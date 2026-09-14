package com.bloxbean.cardano.yaci.core.network.handlers;

import com.bloxbean.cardano.client.crypto.bip32.util.BytesUtil;
import com.bloxbean.cardano.yaci.core.util.CborByteScanner;
import com.bloxbean.cardano.yaci.core.util.CborByteScanner.CborSlice;
import lombok.Data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Data
public class ProtocolChannel {
    private byte[] bytes;
    private int scanOffset;
    private List<CborSlice> completeFrames;

    public ProtocolChannel() {
        this.bytes = new byte[0];
        this.completeFrames = new ArrayList<>();
    }

    public void append(byte[] payload) {
        bytes = BytesUtil.merge(bytes, payload);
    }

    public List<CborSlice> scanCompleteFrames() {
        List<CborSlice> newFrames = CborByteScanner.completeTopLevelItems(bytes, scanOffset);
        if (!newFrames.isEmpty()) {
            completeFrames.addAll(newFrames);
            scanOffset = newFrames.get(newFrames.size() - 1).endOffset();
        }

        return completeFrames;
    }

    public void discardBytes(int consumedLength) {
        if (consumedLength <= 0)
            return;

        bytes = Arrays.copyOfRange(bytes, consumedLength, bytes.length);
        scanOffset = Math.max(0, scanOffset - consumedLength);

        List<CborSlice> shiftedFrames = new ArrayList<>();
        for (CborSlice frame : completeFrames) {
            if (frame.endOffset() <= consumedLength)
                continue;
            if (frame.startOffset() < consumedLength)
                continue;

            shiftedFrames.add(new CborSlice(frame.startOffset() - consumedLength,
                    frame.endOffset() - consumedLength,
                    frame.majorType(),
                    frame.untaggedMajorType()));
        }
        completeFrames = shiftedFrames;
    }

    public void clear() {
        bytes = new byte[0];
        scanOffset = 0;
        completeFrames.clear();
    }
}
