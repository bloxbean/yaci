package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.SimpleValue;
import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.network.handlers.MiniProtoStreamingByteToMessageDecoder;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgent;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.messages.MsgBlock;
import com.bloxbean.cardano.yaci.core.util.CborLoader;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * cbor-java's decoder tags its shared {@link SimpleValue#NULL} when it reads {@code d818f6}, so every later null in the
 * JVM encodes with that tag, for the rest of the run. A peer chooses those bytes, so nothing on the way to a block may
 * reach that decoder.
 */
class SharedNullPoisonTest {
    private static final byte[] FIXTURE = CborLoader.getHexBytes("block/preprod292683.txt");
    private static final String TAGGED_NULL = "d818f6";

    @BeforeEach
    void startClean() {
        while (SimpleValue.NULL.hasTag()) SimpleValue.NULL.removeTag();
    }

    @Test
    void aTaggedNullAnywhereInABlockLeavesLaterParsingUnaffected() {
        Block healthy = BlockSerializer.INSTANCE.deserialize(FIXTURE);
        CborSpan block = CborSpan.of(FIXTURE).get(1);
        CborSpan header = block.get(0);
        List<byte[]> inputs = List.of(
                // An unknown key in a transaction body, as the body extractor decoded it
                replacing(block.get(1).get(0), "a4" + "0080" + "0180" + "0200" + "1863" + TAGGED_NULL),
                // The previous hash in a header
                replacing(header.get(0).get(2), TAGGED_NULL),
                // A datum and an unknown key in a witness set
                replacing(block.get(2).get(0), "a10481" + TAGGED_NULL),
                replacing(block.get(2).get(0), "a11863" + TAGGED_NULL),
                // Metadata, which is decoded again to be converted to JSON
                replacing(block.get(3).entries().get(0).getValue(), "a100" + TAGGED_NULL),
                replacing(block.get(3).entries().get(0).getValue(), "a10081" + TAGGED_NULL),
                // A trailing top-level value
                HexUtil.decodeHexString(HexUtil.encodeHexString(FIXTURE) + TAGGED_NULL));
        for (byte[] input : inputs) {
            BlockfetchAgent agent = new BlockfetchAgent();
            agent.processResponse(new MsgBlock(input)); // Delivered or not, it must leave nothing behind.
            assertNullIsUntagged();
            assertThat(BlockSerializer.INSTANCE.deserialize(FIXTURE)).usingRecursiveComparison().isEqualTo(healthy);
        }
    }

    @Test
    void aTaggedNullInANetworkFrameLeavesLaterParsingUnaffected() {
        EmbeddedChannel channel = new EmbeddedChannel(new MiniProtoStreamingByteToMessageDecoder());
        byte[] payload = HexUtil.decodeHexString("8204" + TAGGED_NULL);
        byte[] frame = new byte[8 + payload.length];
        frame[5] = 3; // Block fetch
        frame[7] = (byte) payload.length;
        System.arraycopy(payload, 0, frame, 8, payload.length);

        channel.writeInbound(Unpooled.wrappedBuffer(frame));

        assertNullIsUntagged();
        channel.finishAndReleaseAll();
    }

    private static void assertNullIsUntagged() {
        assertThat(SimpleValue.NULL.hasTag()).isFalse();
        assertThat(HexUtil.encodeHexString(CborSerializationUtil.serialize(new Array().add(SimpleValue.NULL), false)))
                .isEqualTo("81f6");
    }

    private static byte[] replacing(CborSpan target, String replacement) {
        return CborSpan.of(FIXTURE).replacing(List.of(target), List.of(HexUtil.decodeHexString(replacement)));
    }
}
