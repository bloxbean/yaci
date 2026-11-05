package com.bloxbean.cardano.yaci.core.util;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0002 D8: the delegated decoder and encoder against what they replaced, plain cbor-java, on the same inputs.
 * Trees differ in holding no BREAK item, and the written bytes must not.
 */
class CborCodecDifferentialTest {
    private static final String[] BLOCKS = {"preprod286677", "preprod286853", "preprod287339", "preprod287361",
            "preprod292507", "preprod292683", "preprod4974660", "preprod4974662", "preprod4974666", "preview1300024",
            "preview2587542", "preview_definite_length_4134992"};

    @Test
    void realBlocksReEncodeTheSameNonCanonically() throws Exception {
        for (String block : BLOCKS) {
            byte[] bytes = CborLoader.getHexBytes("block/" + block + ".txt");
            assertThat(CborSerializationUtil.serialize(CborSerializationUtil.deserialize(bytes), false))
                    .as(block).isEqualTo(cborJava(bytes, false));
        }
    }

    @Test
    void realHeadersHashTheSame() throws Exception {
        for (String block : BLOCKS) {
            byte[] bytes = CborLoader.getHexBytes("block/" + block + ".txt");
            ByteArrayOutputStream expected = new ByteArrayOutputStream();
            new CborEncoder(expected).encode(header(CborDecoder.decode(bytes).get(0)));

            byte[] actual = CborSerializationUtil.serialize(header(CborSerializationUtil.deserializeOne(bytes)));
            assertThat(actual).as(block).isEqualTo(expected.toByteArray());
        }
    }

    private static DataItem header(DataItem block) {
        return ((Array) ((Array) block).getDataItems().get(1)).getDataItems().get(0);
    }

    @Test
    void nestedIndefiniteArraysAreWrittenWhole() throws Exception {
        for (String hex : new String[]{"9f9f9fffff9f01ffff", "d901029f9f01ff8102ff", "a1009f019f02ffff",
                "9fa1009f01ffff", "825f4101ff9f00ff", "bf009f01ffff", "d8799f9f00ffff"}) {
            byte[] bytes = HexUtil.decodeHexString(hex);
            assertThat(CborSerializationUtil.serialize(CborSerializationUtil.deserialize(bytes), false))
                    .as(hex).isEqualTo(cborJava(bytes, false));
            assertThat(CborSerializationUtil.serialize(CborSerializationUtil.deserialize(bytes)))
                    .as(hex).isEqualTo(cborJava(bytes, true));
        }
    }

    private static byte[] cborJava(byte[] bytes, boolean canonical) throws Exception {
        List<DataItem> items = CborDecoder.decode(bytes);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CborEncoder encoder = new CborEncoder(out);
        if (!canonical) encoder.nonCanonical();
        encoder.encode(items);
        return out.toByteArray();
    }
}
